// The enqueue boundary. It re-validates whatever the screens allowed, then stores one immutable snapshot atomically.
import type { ItemDraft } from '@cargo/contracts';
import { buildItemVersion, type EditorErrors, type ItemEditor } from '../../entities/item/model.ts';
import { entryKey, newEntry, NO_PAIRING_ID, type OutboxEntry } from '../../entities/outbox/model.ts';
import { isValidItemDraft } from '../../shared/contracts/item-draft.ts';
import { EditorVersionConflict, enqueueEntry, getPhoto, RevisionRaceError } from '../../shared/storage/repository.ts';
import { newUuid } from '../../shared/lib/uuid.ts';

export const CONSENT_VERSION = 'transfer-consent-v1';

export type EnqueueResult =
  | { ok: true; key: string }
  | { ok: false; reason: 'INVALID'; errors: EditorErrors }
  | { ok: false; reason: 'SCHEMA' | 'PHOTO_MISSING' | 'STORAGE' | 'EDITOR_CONFLICT' }
  | { ok: false; reason: 'REVISION_RACE'; latest: number };

/** Next revision for the draft this editor continues, or a fresh draft. */
function nextIdentity(editor: ItemEditor, entries: OutboxEntry[]) {
  if (!editor.continues) return { draftId: newUuid(), clientRevision: 1, expectedLatestRevision: null };
  const revisions = entries.filter((entry) => entry.draft.draftId === editor.continues?.draftId).map((entry) => entry.draft.clientRevision);
  const latest = revisions.length > 0 ? Math.max(...revisions) : null;
  return { draftId: editor.continues.draftId, clientRevision: (latest ?? 0) + 1, expectedLatestRevision: latest };
}

/** expectedSaveId is the stored editor this tab last saw. If another tab has moved on, nothing is enqueued and nothing is deleted. */
export async function enqueueFromEditor(editor: ItemEditor, pairingId: string | null, entries: OutboxEntry[], expectedSaveId: string | null): Promise<EnqueueResult> {
  const now = new Date().toISOString();
  const photoWillSend = editor.photoConsent && editor.photoId !== null;
  const photo = photoWillSend ? await getPhoto(editor.photoId as string).catch(() => undefined) : undefined;
  if (photoWillSend && !photo) return { ok: false, reason: 'PHOTO_MISSING' };

  const built = buildItemVersion(editor, photo ? `sha256:${photo.sha256}` : null, now);
  if (!built.ok) return { ok: false, reason: 'INVALID', errors: built.errors };

  const identity = nextIdentity(editor, entries);
  const draft: ItemDraft = {
    draftId: identity.draftId,
    // Without a pairing the entry waits locally; the user confirms attaching it once a QR is scanned.
    pairingId: pairingId ?? NO_PAIRING_ID,
    ...(editor.base ? { baseItemId: editor.base.itemId, baseItemRevision: editor.base.revision } : {}),
    schemaVersion: 1,
    payload: built.item,
    clientRevision: identity.clientRevision,
  };
  if (!isValidItemDraft(draft)) return { ok: false, reason: 'SCHEMA' };

  const entry = newEntry(draft, { consentVersion: CONSENT_VERSION, dimensionsConsent: true, photoConsent: photo !== undefined }, photo ? photo.id : null, now);
  try {
    await enqueueEntry(entry, { finishesEditor: { expectedSaveId }, expectedLatestRevision: identity.expectedLatestRevision, at: now });
  } catch (cause) {
    if (cause instanceof EditorVersionConflict) return { ok: false, reason: 'EDITOR_CONFLICT' };
    if (cause instanceof RevisionRaceError) return { ok: false, reason: 'REVISION_RACE', latest: cause.latest };
    return { ok: false, reason: 'STORAGE' };
  }
  return { ok: true, key: entryKey(draft.draftId, draft.clientRevision) };
}

/** A new revision of an entry without opening the editor: same content, or the same content without its photo. */
export async function reviseEntry(entry: OutboxEntry, entries: OutboxEntry[], options: { removePhoto: boolean }): Promise<EnqueueResult> {
  const now = new Date().toISOString();
  const revisions = entries.filter((other) => other.draft.draftId === entry.draft.draftId).map((other) => other.draft.clientRevision);
  const latest = Math.max(...revisions);
  const newest = entry.lastFailure?.details?.newestClientRevision;
  const clientRevision = Math.max(latest, typeof newest === 'number' ? newest : 0) + 1;
  const { photoRef: _photoRef, ...withoutPhoto } = entry.draft.payload;
  const payload = options.removePhoto ? withoutPhoto : entry.draft.payload;
  const draft: ItemDraft = { ...entry.draft, clientRevision, payload: { ...payload, updatedAt: now } };
  if (!isValidItemDraft(draft)) return { ok: false, reason: 'SCHEMA' };
  const consent = options.removePhoto ? { ...entry.consent, photoConsent: false } : entry.consent;
  const next = newEntry(draft, consent, options.removePhoto ? null : entry.photoId, now);
  try {
    await enqueueEntry(next, { finishesEditor: null, expectedLatestRevision: latest, at: now });
  } catch (cause) {
    if (cause instanceof RevisionRaceError) return { ok: false, reason: 'REVISION_RACE', latest: cause.latest };
    return { ok: false, reason: 'STORAGE' };
  }
  return { ok: true, key: next.key };
}
