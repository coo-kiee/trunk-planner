// The outbox state machine, pure and monotonic: a late or duplicate answer can never undo what the vehicle already decided.
import type { ItemDraft } from '@cargo/contracts';

export const NO_PAIRING_ID = '00000000-0000-0000-0000-000000000000';

export type OutboxStatus =
  | 'pending' | 'sending' | 'received' | 'conflict' | 'accepted' | 'rejected' | 'superseded' | 'expired' | 'revoked' | 'unknown' | 'blocked';

export interface TransferConsent { consentVersion: string; dimensionsConsent: true; photoConsent: boolean }
export interface Receipt { pairingId: string; payloadHash: string; receivedAt: string; expiresAt: string }
export interface TerminalAck {
  result: 'VEHICLE_ACCEPTED' | 'VEHICLE_REJECTED';
  acceptedItemId?: string;
  acceptedRevision?: number;
  rejectCode?: 'USER_DISCARDED' | 'USER_KEPT_VEHICLE_VERSION' | 'VALIDATION_FAILED';
}
export interface ConflictAck { authoritativeItemId: string; authoritativeRevision: number }
export type FailureKind = 'NETWORK' | 'SERVER' | 'RATE_LIMITED';
export interface LastFailure { kind: FailureKind | 'POLICY'; code: string; at: string; details?: Record<string, unknown> }

export interface OutboxEntry {
  /** `${draftId}:${clientRevision}` */
  key: string;
  /** Immutable after enqueue, apart from pairingId when the user confirms re-pairing. */
  draft: ItemDraft;
  consent: TransferConsent;
  photoId: string | null;
  status: OutboxStatus;
  createdAt: string;
  updatedAt: string;
  receipt: Receipt | null;
  terminalAck: TerminalAck | null;
  conflict: ConflictAck | null;
  lastFailure: LastFailure | null;
  attempts: number;
  /** Null means automatic retry has stopped; a manual retry or foreground event restarts it. */
  nextAttemptAt: string | null;
  /** A newer revision of this draft exists locally. */
  replacedBy: number | null;
  /** The relay may or may not have this draft under an earlier pairing, shown after re-pairing an unknown entry. */
  resentAfterUnknown: boolean;
}

export const entryKey = (draftId: string, clientRevision: number): string => `${draftId}:${clientRevision}`;

export function newEntry(draft: ItemDraft, consent: TransferConsent, photoId: string | null, nowIso: string): OutboxEntry {
  return {
    key: entryKey(draft.draftId, draft.clientRevision), draft, consent, photoId, status: 'pending',
    createdAt: nowIso, updatedAt: nowIso, receipt: null, terminalAck: null, conflict: null, lastFailure: null,
    attempts: 0, nextAttemptAt: nowIso, replacedBy: null, resentAfterUnknown: false,
  };
}

const TERMINAL = new Set<OutboxStatus>(['accepted', 'rejected', 'superseded', 'expired', 'revoked']);
export const isTerminal = (status: OutboxStatus): boolean => TERMINAL.has(status);
export const isUnsent = (entry: OutboxEntry): boolean => entry.receipt === null && !isTerminal(entry.status);
export const canSend = (status: OutboxStatus): boolean => status === 'pending';
export const needsStatusRead = (status: OutboxStatus): boolean => status === 'received' || status === 'conflict';

/** Only entries the vehicle has not decided may move to a new pairing; an accepted result is never re-claimed. */
export function canRepack(entry: OutboxEntry, pairingId: string): boolean {
  if (entry.draft.pairingId === pairingId || entry.terminalAck) return false;
  return entry.replacedBy === null && ['pending', 'expired', 'revoked', 'unknown', 'blocked'].includes(entry.status);
}

export interface RelayStatusBody {
  pairingId: string;
  payloadHash: string;
  state: 'PENDING' | 'CONFLICT_REPORTED' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED' | 'EXPIRED' | 'REVOKED';
  receivedAt: string;
  expiresAt: string;
  conflict?: ConflictAck | null;
  terminalAck?: TerminalAck | null;
}

export type OutboxEvent =
  | { type: 'SEND_STARTED'; at: string }
  | { type: 'SEND_RETRY_LATER'; at: string; failure: LastFailure; nextAttemptAt: string | null }
  | { type: 'STATUS'; at: string; body: RelayStatusBody }
  | { type: 'RELAY_ERROR'; at: string; code: string; details?: Record<string, unknown> }
  | { type: 'RECOVER_AFTER_RELOAD'; at: string }
  | { type: 'MANUAL_RETRY'; at: string }
  | { type: 'REPLACED_LOCALLY'; at: string; clientRevision: number }
  | { type: 'REPACK'; at: string; pairingId: string };

function touch(entry: OutboxEntry, at: string, patch: Partial<OutboxEntry>): OutboxEntry {
  return { ...entry, ...patch, updatedAt: at };
}

function applyStatus(entry: OutboxEntry, at: string, body: RelayStatusBody): OutboxEntry {
  const receipt: Receipt = { pairingId: body.pairingId, payloadHash: body.payloadHash, receivedAt: body.receivedAt, expiresAt: body.expiresAt };
  const base = { receipt, lastFailure: null, nextAttemptAt: null };
  const conflict = body.conflict ?? entry.conflict;
  switch (body.state) {
    case 'PENDING':
      // A duplicate receipt or stale read cannot move a conflict backwards.
      return touch(entry, at, { ...base, status: entry.status === 'conflict' ? 'conflict' : 'received' });
    case 'CONFLICT_REPORTED':
      // The ack body carries the numbers. Without them the state is not shown as a conflict yet.
      return touch(entry, at, { ...base, conflict, status: conflict ? 'conflict' : 'received' });
    case 'ACCEPTED':
      if (body.terminalAck?.result !== 'VEHICLE_ACCEPTED') return touch(entry, at, { ...base, status: 'received' });
      return touch(entry, at, { ...base, conflict, terminalAck: body.terminalAck, status: 'accepted' });
    case 'REJECTED':
      if (body.terminalAck?.result !== 'VEHICLE_REJECTED') return touch(entry, at, { ...base, status: 'received' });
      return touch(entry, at, { ...base, conflict, terminalAck: body.terminalAck, status: 'rejected' });
    case 'SUPERSEDED':
      return touch(entry, at, { ...base, conflict, status: 'superseded' });
    case 'EXPIRED':
      return touch(entry, at, { ...base, conflict, status: 'expired' });
    case 'REVOKED':
      return touch(entry, at, { ...base, conflict, status: 'revoked' });
  }
}

/** Codes meaning the ack can no longer be read. After a receipt that is "unknown", never success and never failure. */
const ACK_UNREADABLE = new Set(['PAIRING_REVOKED', 'CREDENTIAL_EXPIRED', 'UNAUTHENTICATED', 'DRAFT_NOT_FOUND']);

function applyRelayError(entry: OutboxEntry, at: string, code: string, details?: Record<string, unknown>): OutboxEntry {
  const failure: LastFailure = { kind: 'POLICY', code, at, ...(details ? { details } : {}) };
  if (ACK_UNREADABLE.has(code)) {
    if (entry.receipt) return touch(entry, at, { status: 'unknown', lastFailure: failure, nextAttemptAt: null });
    const status: OutboxStatus = code === 'PAIRING_REVOKED' ? 'revoked' : code === 'DRAFT_NOT_FOUND' ? 'pending' : 'expired';
    return touch(entry, at, { status, lastFailure: failure, nextAttemptAt: null });
  }
  if (code === 'DRAFT_EXPIRED' || code === 'PAIRING_EXPIRED') return touch(entry, at, { status: 'expired', lastFailure: failure, nextAttemptAt: null });
  return touch(entry, at, { status: 'blocked', lastFailure: failure, nextAttemptAt: null });
}

export function reduceOutbox(entry: OutboxEntry, event: OutboxEvent): OutboxEntry {
  // A recorded vehicle decision is final locally: no event may overwrite or re-open it.
  if (entry.terminalAck) return entry;
  const at = event.at;
  switch (event.type) {
    case 'SEND_STARTED':
      return entry.status === 'pending' ? touch(entry, at, { status: 'sending', attempts: entry.attempts + 1 }) : entry;
    case 'SEND_RETRY_LATER':
      return entry.status === 'sending' || entry.status === 'pending'
        ? touch(entry, at, { status: 'pending', lastFailure: event.failure, nextAttemptAt: event.nextAttemptAt })
        : touch(entry, at, { lastFailure: event.failure });
    case 'STATUS':
      if (isTerminal(entry.status) && entry.status !== 'revoked' && entry.status !== 'expired' && event.body.state !== 'ACCEPTED' && event.body.state !== 'REJECTED') return entry;
      return applyStatus(entry, at, event.body);
    case 'RELAY_ERROR':
      return isTerminal(entry.status) ? entry : applyRelayError(entry, at, event.code, event.details);
    case 'RECOVER_AFTER_RELOAD':
      return entry.status === 'sending' ? touch(entry, at, { status: 'pending', nextAttemptAt: at }) : entry;
    case 'MANUAL_RETRY':
      return entry.status === 'pending' ? touch(entry, at, { nextAttemptAt: at, attempts: 0 }) : entry;
    case 'REPLACED_LOCALLY':
      // Never sent means nothing to wait for; otherwise keep reading in case the vehicle already decided this revision.
      return touch(entry, at, { replacedBy: event.clientRevision, status: entry.receipt ? entry.status : 'superseded', nextAttemptAt: null });
    case 'REPACK': {
      const wasUnknown = entry.status === 'unknown';
      return touch(entry, at, {
        draft: { ...entry.draft, pairingId: event.pairingId },
        status: 'pending', receipt: null, conflict: null, lastFailure: null, attempts: 0, nextAttemptAt: at,
        resentAfterUnknown: entry.resentAfterUnknown || wasUnknown,
      });
    }
  }
}

/** What the vehicle has actually confirmed about an item, kept apart from anything still waiting in the outbox. */
export interface ConfirmedItem {
  /** payload.id this phone sent. */
  sentItemId: string;
  /** The vehicle's real id, which differs when the user chose "separate item" there. */
  acceptedItemId: string;
  acceptedRevision: number;
  draftKey: string;
  confirmedAt: string;
}

export function confirmationOf(entry: OutboxEntry): ConfirmedItem | null {
  const ack = entry.terminalAck;
  if (ack?.result !== 'VEHICLE_ACCEPTED' || !ack.acceptedItemId || ack.acceptedRevision === undefined) return null;
  return { sentItemId: entry.draft.payload.id, acceptedItemId: ack.acceptedItemId, acceptedRevision: ack.acceptedRevision, draftKey: entry.key, confirmedAt: entry.updatedAt };
}
