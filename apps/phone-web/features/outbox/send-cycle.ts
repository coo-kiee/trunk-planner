// One foreground pass over the outbox. Pure decisions come from entities/outbox; this file only performs the effects.
import { canSend, isTerminal, needsStatusRead, type OutboxEntry } from '../../entities/outbox/model.ts';
import { classifyFailure, nextRetryDelayMs, nextStatusDelayMs, STATUS_READ_MIN_MS, type RelayFailure } from '../../entities/outbox/retry.ts';
import type { PairingRecord } from '../../entities/pairing/model.ts';
import { readDraftStatus, submitDraft, type SubmitPayload } from '../../shared/api/relay-client.ts';
import { toBase64 } from '../../shared/lib/base64.ts';
import { applyEntryEvent, getPhoto, loadSnapshot, savePairing } from '../../shared/storage/repository.ts';

export interface CycleContext {
  relayBaseUrl: string;
  pairing: PairingRecord;
  force: boolean;
  signal: AbortSignal;
  /** In-memory read pacing per entry; losing it on reload just means one earlier read. */
  reads: Map<string, { count: number; nextAt: number }>;
  /** Called after each committed state change, so the screen shows "sending" while the request is still out. */
  onProgress: () => void;
}

const PAIRING_ENDED: Record<string, PairingRecord['state']> = { PAIRING_REVOKED: 'REVOKED', CREDENTIAL_EXPIRED: 'EXPIRED', UNAUTHENTICATED: 'EXPIRED' };

async function buildPayload(entry: OutboxEntry): Promise<SubmitPayload | 'PHOTO_MISSING'> {
  if (!entry.consent.photoConsent || !entry.photoId) return { consent: entry.consent, draft: entry.draft };
  const photo = await getPhoto(entry.photoId);
  if (!photo) return 'PHOTO_MISSING';
  const bytes = new Uint8Array(await photo.blob.arrayBuffer());
  return { consent: entry.consent, draft: entry.draft, photo: { mime: photo.mime, dataBase64: toBase64(bytes) } };
}

/** Returns true when the relay says this credential is over, which ends the pass. */
async function handleFailure(entry: OutboxEntry, failure: RelayFailure, attempts: number, context: CycleContext, reading: boolean): Promise<boolean> {
  const now = new Date();
  const decision = classifyFailure(failure);
  if (decision.action === 'retry') {
    const lastFailure = { kind: decision.kind, code: decision.code, at: now.toISOString() };
    const delay = reading ? null : nextRetryDelayMs(decision.kind, attempts);
    const nextAttemptAt = delay === null ? null : new Date(now.getTime() + delay).toISOString();
    await applyEntryEvent(entry.key, { type: 'SEND_RETRY_LATER', at: now.toISOString(), failure: lastFailure, nextAttemptAt });
    return false;
  }
  const details = failure.kind === 'http' ? failure.details : undefined;
  await applyEntryEvent(entry.key, { type: 'RELAY_ERROR', at: now.toISOString(), code: decision.code, ...(details ? { details } : {}) });
  const ended = PAIRING_ENDED[decision.code];
  if (!ended) return false;
  await savePairing({ ...context.pairing, state: ended });
  // The credential covers every entry of this pairing, so all of them learn the same fact now.
  const snapshot = await loadSnapshot();
  const at = now.toISOString();
  const others = snapshot.entries.filter((other) => other.key !== entry.key && other.draft.pairingId === context.pairing.pairingId && !isTerminal(other.status));
  await Promise.all(others.map((other) => applyEntryEvent(other.key, { type: 'RELAY_ERROR', at, code: decision.code })));
  return true;
}

async function sendOne(entry: OutboxEntry, context: CycleContext): Promise<boolean> {
  const started = new Date().toISOString();
  // The claim response lists what this relay accepts. Sending anything else could only fail, so it stops here without a retry.
  if (!context.pairing.consentVersions.includes(entry.consent.consentVersion)) {
    await applyEntryEvent(entry.key, { type: 'RELAY_ERROR', at: started, code: 'CONSENT_VERSION_UNSUPPORTED' });
    return false;
  }
  const payload = await buildPayload(entry);
  if (payload === 'PHOTO_MISSING') {
    await applyEntryEvent(entry.key, { type: 'RELAY_ERROR', at: started, code: 'PHOTO_REF_INVALID' });
    return false;
  }
  await applyEntryEvent(entry.key, { type: 'SEND_STARTED', at: started });
  context.onProgress();
  const result = await submitDraft(context.relayBaseUrl, context.pairing.phoneToken, payload, context.signal);
  if (context.signal.aborted) {
    await applyEntryEvent(entry.key, { type: 'RECOVER_AFTER_RELOAD', at: new Date().toISOString() });
    return false;
  }
  if (result.ok) {
    await applyEntryEvent(entry.key, { type: 'STATUS', at: new Date().toISOString(), body: result.body });
    return false;
  }
  return handleFailure(entry, result, entry.attempts + 1, context, false);
}

async function readOne(entry: OutboxEntry, context: CycleContext): Promise<boolean> {
  const result = await readDraftStatus(context.relayBaseUrl, context.pairing.phoneToken, entry.draft.draftId, entry.draft.clientRevision, context.signal);
  if (context.signal.aborted) return false;
  if (result.ok) {
    await applyEntryEvent(entry.key, { type: 'STATUS', at: new Date().toISOString(), body: result.body });
    return false;
  }
  return handleFailure(entry, result, 0, context, true);
}

/** When something should next happen, judged from what is stored now rather than what the pass started with. */
function nextDueAfter(entries: OutboxEntry[], context: CycleContext): number | null {
  const times = entries.filter((entry) => entry.draft.pairingId === context.pairing.pairingId).flatMap((entry) => {
    if (canSend(entry.status)) return entry.nextAttemptAt ? [Date.parse(entry.nextAttemptAt)] : [];
    if (needsStatusRead(entry.status)) return [context.reads.get(entry.key)?.nextAt ?? Date.now() + STATUS_READ_MIN_MS];
    return [];
  });
  return times.length > 0 ? Math.min(...times) : null;
}

/** Runs due sends and reads in order. Returns the time of the next due action, or null if nothing is scheduled. */
export async function runSendCycle(entries: OutboxEntry[], context: CycleContext): Promise<number | null> {
  for (const entry of entries) {
    if (context.signal.aborted) return null;
    if (entry.draft.pairingId !== context.pairing.pairingId) continue;

    if (canSend(entry.status)) {
      const dueAt = entry.nextAttemptAt ? Date.parse(entry.nextAttemptAt) : null;
      if (!context.force && (dueAt === null || dueAt > Date.now())) continue;
      if (await sendOne(entry, context)) return null;
      context.onProgress();
    } else if (needsStatusRead(entry.status)) {
      const pacing = context.reads.get(entry.key) ?? { count: 0, nextAt: 0 };
      if (!context.force && pacing.nextAt > Date.now()) continue;
      if (await readOne(entry, context)) return null;
      context.reads.set(entry.key, { count: pacing.count + 1, nextAt: Date.now() + nextStatusDelayMs(pacing.count) });
      context.onProgress();
    }
  }
  return nextDueAfter((await loadSnapshot()).entries, context);
}
