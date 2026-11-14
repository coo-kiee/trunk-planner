// All persistence for the phone. Each user-visible save is one transaction, and "saved" is reported only after it committed.
import { confirmationOf, entryKey, reduceOutbox, type ConfirmedItem, type OutboxEntry, type OutboxEvent } from '../../entities/outbox/model.ts';
import type { ItemEditor } from '../../entities/item/model.ts';
import type { PairingRecord } from '../../entities/pairing/model.ts';
import { newUuid } from '../lib/uuid.ts';
import { asPromise, STORES, StorageError, transact } from './idb.ts';
import { isConfirmedItem, isOutboxEntry, isPairingRecord, isStoredEditor, isStoredPhoto } from './integrity.ts';

const CURRENT = 'current';

/** saveId is unique per save, so a compare-and-set cannot be fooled when an editor is finished and another one started. */
export interface StoredEditor { saveId: string; state: ItemEditor }
export interface StoredPhoto { id: string; blob: Blob; mime: string; sha256: string; width: number; height: number; bytes: number }
export interface Snapshot {
  pairing: PairingRecord | null;
  editor: StoredEditor | null;
  entries: OutboxEntry[];
  confirmed: ConfirmedItem[];
  /** Records this version cannot read. They are preserved in the database, unused until the user explicitly discards them. */
  corruptKeys: string[];
}

const keyOf = (value: unknown, field: string): string => String((value as Record<string, unknown> | null)?.[field] ?? '알 수 없음');

/**
 * The editor and pairing are single records that the next save would overwrite, so an unreadable one moves to quarantine.
 * It re-reads inside the write transaction: another tab may have replaced or moved it meanwhile.
 */
function quarantineSingletons(): Promise<void> {
  return transact([STORES.pairing, STORES.editor, STORES.quarantine], 'readwrite', async (tx) => {
    const quarantine = tx.objectStore(STORES.quarantine);
    const checks = [[STORES.pairing, isPairingRecord], [STORES.editor, isStoredEditor]] as const;
    for (const [name, isReadable] of checks) {
      const store = tx.objectStore(name);
      const value: unknown = await asPromise(store.get(CURRENT));
      if (value === undefined || isReadable(value)) continue;
      quarantine.put({ store: name, value }, `${name}:${newUuid()}`);
      store.delete(CURRENT);
    }
  });
}

export async function loadSnapshot(): Promise<Snapshot> {
  const read = () => transact([STORES.pairing, STORES.editor, STORES.outbox, STORES.confirmed, STORES.quarantine], 'readonly', async (tx) => {
    const [pairing, editor, entries, confirmed, quarantined] = await Promise.all([
      asPromise(tx.objectStore(STORES.pairing).get(CURRENT)) as Promise<unknown>,
      asPromise(tx.objectStore(STORES.editor).get(CURRENT)) as Promise<unknown>,
      asPromise(tx.objectStore(STORES.outbox).getAll()) as Promise<unknown[]>,
      asPromise(tx.objectStore(STORES.confirmed).getAll()) as Promise<unknown[]>,
      asPromise(tx.objectStore(STORES.quarantine).getAllKeys()),
    ]);
    return { pairing, editor, entries, confirmed, quarantined };
  });
  let raw = await read();
  const unreadableSingle = (raw.pairing !== undefined && !isPairingRecord(raw.pairing)) || (raw.editor !== undefined && !isStoredEditor(raw.editor));
  // If the move itself fails (a full disk, say), the record stays put: still unused, and saveEditor refuses to overwrite it.
  if (unreadableSingle) {
    await quarantineSingletons().catch(() => undefined);
    raw = await read();
  }
  const entries = raw.entries.filter(isOutboxEntry).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const singles = [raw.pairing !== undefined && !isPairingRecord(raw.pairing) ? 'pairing' : null, raw.editor !== undefined && !isStoredEditor(raw.editor) ? 'editor' : null].filter((name) => name !== null);
  return {
    pairing: isPairingRecord(raw.pairing) ? raw.pairing : null,
    editor: isStoredEditor(raw.editor) ? raw.editor : null,
    entries,
    confirmed: raw.confirmed.filter(isConfirmedItem),
    corruptKeys: [
      ...singles,
      ...raw.entries.filter((value) => !isOutboxEntry(value)).map((value) => `outbox:${keyOf(value, 'key')}`),
      ...raw.confirmed.filter((value) => !isConfirmedItem(value)).map((value) => `confirmed:${keyOf(value, 'acceptedItemId')}`),
      ...raw.quarantined.map((key) => `quarantine:${String(key)}`),
    ],
  };
}

/** Only on the user's explicit request, and only what could not be read. Readable records are untouched. */
export function discardCorruptRecords(): Promise<void> {
  return transact([STORES.outbox, STORES.confirmed, STORES.quarantine], 'readwrite', async (tx) => {
    const sweep = async (name: typeof STORES.outbox | typeof STORES.confirmed, isReadable: (value: unknown) => boolean) => {
      const store = tx.objectStore(name);
      const [values, keys] = await Promise.all([asPromise(store.getAll()), asPromise(store.getAllKeys())]);
      (values as unknown[]).forEach((value, index) => {
        if (!isReadable(value)) store.delete(keys[index] as IDBValidKey);
      });
    };
    await sweep(STORES.outbox, isOutboxEntry);
    await sweep(STORES.confirmed, isConfirmedItem);
    tx.objectStore(STORES.quarantine).clear();
  });
}

export class EditorVersionConflict extends Error {}

/** Compare-and-set, so a second tab's save is reported instead of silently overwritten. */
export async function saveEditor(state: ItemEditor, expectedSaveId: string | null): Promise<string> {
  return transact([STORES.editor], 'readwrite', async (tx) => {
    const store = tx.objectStore(STORES.editor);
    const current: unknown = await asPromise(store.get(CURRENT));
    const currentSaveId = isStoredEditor(current) ? current.saveId : null;
    if (current !== undefined && !isStoredEditor(current)) throw new StorageError('WRITE_FAILED'); // Never overwrite what could not be read.
    if (currentSaveId !== expectedSaveId) throw new EditorVersionConflict();
    const saveId = newUuid();
    store.put({ saveId, state } satisfies StoredEditor, CURRENT);
    return saveId;
  }).catch((cause: unknown) => {
    if ((cause as StorageError).cause instanceof EditorVersionConflict) throw new EditorVersionConflict();
    throw cause;
  });
}

export const clearEditor = () => transact([STORES.editor], 'readwrite', (tx) => { tx.objectStore(STORES.editor).delete(CURRENT); });
export const savePairing = (record: PairingRecord) => transact([STORES.pairing], 'readwrite', (tx) => { tx.objectStore(STORES.pairing).put(record, CURRENT); });
export const clearPairing = () => transact([STORES.pairing], 'readwrite', (tx) => { tx.objectStore(STORES.pairing).delete(CURRENT); });
export const putPhoto = (photo: StoredPhoto) => transact([STORES.photos], 'readwrite', (tx) => { tx.objectStore(STORES.photos).put(photo, photo.id); });
export const getPhoto = (id: string) => transact([STORES.photos], 'readonly', async (tx) => {
  const value: unknown = await asPromise(tx.objectStore(STORES.photos).get(id));
  return isStoredPhoto(value) ? (value as StoredPhoto) : undefined; // An unreadable photo stays stored but is treated as missing.
});

/** Removes a photo only when neither the editor nor any outbox entry still refers to it. */
export function releasePhotoIfUnused(id: string): Promise<boolean> {
  return transact([STORES.photos, STORES.outbox, STORES.editor], 'readwrite', async (tx) => {
    const [entries, editor] = await Promise.all([asPromise(tx.objectStore(STORES.outbox).getAll()), asPromise(tx.objectStore(STORES.editor).get(CURRENT))]);
    // Unreadable records might refer to it, so the photo stays until they are dealt with.
    const allEntries = entries as unknown[];
    const editorUsesIt = editor !== undefined && (!isStoredEditor(editor) || editor.state.photoId === id);
    const used = editorUsesIt || allEntries.some((entry) => !isOutboxEntry(entry) || entry.photoId === id);
    if (!used) tx.objectStore(STORES.photos).delete(id);
    return !used;
  });
}

export class RevisionRaceError extends Error {
  readonly latest: number;
  constructor(latest: number) {
    super('revision race');
    this.latest = latest;
  }
}

function latestRevision(rawEntries: unknown[], draftId: string): OutboxEntry | null {
  return rawEntries.filter(isOutboxEntry).filter((entry) => entry.draft.draftId === draftId).sort((a, b) => b.draft.clientRevision - a.draft.clientRevision)[0] ?? null;
}

/**
 * Atomically stores the immutable snapshot, marks the revision it replaces and, when requested, finishes the editor.
 * Two tabs cannot create the same revision: the second sees a newer latest and gets RevisionRaceError.
 * Finishing the editor is a compare-and-set as well: a tab whose view is stale gets EditorVersionConflict and nothing changes.
 */
export function enqueueEntry(entry: OutboxEntry, options: { finishesEditor: { expectedSaveId: string | null } | null; expectedLatestRevision: number | null; at: string }): Promise<void> {
  return transact([STORES.outbox, STORES.editor], 'readwrite', async (tx) => {
    const editorStore = tx.objectStore(STORES.editor);
    if (options.finishesEditor) {
      const current: unknown = await asPromise(editorStore.get(CURRENT));
      const currentSaveId = isStoredEditor(current) ? current.saveId : null;
      if (current !== undefined && !isStoredEditor(current)) throw new StorageError('WRITE_FAILED');
      if (currentSaveId !== options.finishesEditor.expectedSaveId) throw new EditorVersionConflict();
    }
    const store = tx.objectStore(STORES.outbox);
    const entries = (await asPromise(store.getAll())) as unknown[];
    const latest = latestRevision(entries, entry.draft.draftId);
    if ((latest?.draft.clientRevision ?? null) !== options.expectedLatestRevision) throw new RevisionRaceError(latest?.draft.clientRevision ?? 0);
    if (latest) store.put(reduceOutbox(latest, { type: 'REPLACED_LOCALLY', at: options.at, clientRevision: entry.draft.clientRevision }));
    store.put(entry);
    if (options.finishesEditor) editorStore.delete(CURRENT);
  }).catch((cause: unknown) => {
    const inner = (cause as StorageError).cause;
    if (inner instanceof RevisionRaceError || inner instanceof EditorVersionConflict) throw inner;
    throw cause;
  });
}

/** Applies one event in one transaction, recording a vehicle confirmation together with the entry that proves it. */
export function applyEntryEvent(key: string, event: OutboxEvent): Promise<OutboxEntry | null> {
  return transact([STORES.outbox, STORES.confirmed], 'readwrite', async (tx) => {
    const store = tx.objectStore(STORES.outbox);
    const current: unknown = await asPromise(store.get(key));
    if (!isOutboxEntry(current)) return null;
    const next = reduceOutbox(current, event);
    if (next === current) return current;
    store.put(next);
    const confirmed = confirmationOf(next);
    if (confirmed) {
      const confirmedStore = tx.objectStore(STORES.confirmed);
      const known: unknown = await asPromise(confirmedStore.get(confirmed.acceptedItemId));
      // A late answer about an older revision must not lower what the vehicle has confirmed. An unreadable record is not overwritten.
      if (known === undefined || (isConfirmedItem(known) && known.acceptedRevision <= confirmed.acceptedRevision)) confirmedStore.put(confirmed);
    }
    return next;
  });
}

export const deleteEntry = (key: string) => transact([STORES.outbox], 'readwrite', (tx) => { tx.objectStore(STORES.outbox).delete(key); });

/** Local data only. It cannot recall anything the relay or the vehicle already holds. */
export const wipeLocalData = () => transact(Object.values(STORES), 'readwrite', (tx) => {
  for (const name of Object.values(STORES)) tx.objectStore(name).clear();
});

export { entryKey };
