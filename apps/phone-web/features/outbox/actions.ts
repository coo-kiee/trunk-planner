// User-confirmed outbox actions that act on several stored entries.
import { canRepack, isTerminal, type OutboxEntry } from '../../entities/outbox/model.ts';
import { applyEntryEvent } from '../../shared/storage/repository.ts';

/** Moves undecided entries to the new pairing. Accepted results stay as they are, and nothing claims a re-acceptance. */
export async function repackEntries(entries: OutboxEntry[], pairingId: string): Promise<number> {
  const at = new Date().toISOString();
  const movable = entries.filter((entry) => canRepack(entry, pairingId));
  await Promise.all(movable.map((entry) => applyEntryEvent(entry.key, { type: 'REPACK', at, pairingId })));
  return movable.length;
}

/** After a pairing ends, unsent entries end but stay local, and received ones become "unknown", never success. */
export async function markPairingEnded(entries: OutboxEntry[], pairingId: string, code: 'PAIRING_REVOKED' | 'CREDENTIAL_EXPIRED'): Promise<void> {
  const at = new Date().toISOString();
  const affected = entries.filter((entry) => entry.draft.pairingId === pairingId && !isTerminal(entry.status));
  await Promise.all(affected.map((entry) => applyEntryEvent(entry.key, { type: 'RELAY_ERROR', at, code })));
}

/** A new QR replaces the credential, so answers about drafts received under the old one can no longer be read. */
export async function markOldPairingUnreadable(entries: OutboxEntry[], newPairingId: string): Promise<void> {
  const at = new Date().toISOString();
  const affected = entries.filter((entry) => entry.draft.pairingId !== newPairingId && entry.receipt !== null && !isTerminal(entry.status));
  await Promise.all(affected.map((entry) => applyEntryEvent(entry.key, { type: 'RELAY_ERROR', at, code: 'UNAUTHENTICATED' })));
}
