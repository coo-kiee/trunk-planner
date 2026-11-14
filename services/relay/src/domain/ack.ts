// Typed vehicle acknowledgement and the draft state machine. The relay checks shape and transition only:
// item authority and revision comparison stay in the vehicle Room database.
import { RelayError } from './errors.ts';
import { canonicalHash } from './hash.ts';

export const ACTIVE_STATES = ['PENDING', 'CONFLICT_REPORTED'] as const;
export type DraftState = 'PENDING' | 'CONFLICT_REPORTED' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED' | 'EXPIRED' | 'REVOKED';
export type AckKind = 'CONFLICT' | 'TERMINAL';

export type AckBody =
  | { ackVersion: 1; expectedState: DraftState; payloadHash: string; result: 'VEHICLE_ACCEPTED'; acceptedItemId: string; acceptedRevision: number }
  | { ackVersion: 1; expectedState: DraftState; payloadHash: string; result: 'CONFLICT'; authoritativeItemId: string; authoritativeRevision: number }
  | { ackVersion: 1; expectedState: DraftState; payloadHash: string; result: 'VEHICLE_REJECTED'; rejectCode: RejectCode };

const REJECT_CODES = ['USER_DISCARDED', 'USER_KEPT_VEHICLE_VERSION', 'VALIDATION_FAILED'] as const;
type RejectCode = (typeof REJECT_CODES)[number];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH = /^[0-9a-f]{64}$/;
const isRevision = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 1;

const invalid = () => new RelayError(422, 'ACK_INVALID');

export function parseAck(body: unknown): { ack: AckBody; kind: AckKind; sha256: string; nextState: DraftState } {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) throw invalid();
  const raw = body as Record<string, unknown>;
  const common = ['ackVersion', 'expectedState', 'payloadHash', 'result'];
  const allow = (extra: string[]) => {
    if (Object.keys(raw).some((key) => ![...common, ...extra].includes(key))) throw invalid();
  };
  if (raw.ackVersion !== 1 || typeof raw.payloadHash !== 'string' || !HASH.test(raw.payloadHash)) throw invalid();
  if (raw.expectedState !== 'PENDING' && raw.expectedState !== 'CONFLICT_REPORTED') throw invalid();
  const ack = raw as unknown as AckBody;
  let kind: AckKind = 'TERMINAL';
  let nextState: DraftState;
  if (raw.result === 'VEHICLE_ACCEPTED') {
    allow(['acceptedItemId', 'acceptedRevision']);
    if (typeof raw.acceptedItemId !== 'string' || !UUID.test(raw.acceptedItemId) || !isRevision(raw.acceptedRevision)) throw invalid();
    nextState = 'ACCEPTED';
  } else if (raw.result === 'CONFLICT') {
    allow(['authoritativeItemId', 'authoritativeRevision']);
    if (typeof raw.authoritativeItemId !== 'string' || !UUID.test(raw.authoritativeItemId) || !isRevision(raw.authoritativeRevision)) throw invalid();
    if (raw.expectedState !== 'PENDING') throw invalid();
    kind = 'CONFLICT';
    nextState = 'CONFLICT_REPORTED';
  } else if (raw.result === 'VEHICLE_REJECTED') {
    allow(['rejectCode']);
    if (!REJECT_CODES.includes(raw.rejectCode as RejectCode)) throw invalid();
    nextState = 'REJECTED';
  } else {
    throw invalid();
  }
  return { ack, kind, sha256: canonicalHash(ack), nextState };
}
