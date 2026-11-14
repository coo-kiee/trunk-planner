import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canRepack, confirmationOf, newEntry, NO_PAIRING_ID, reduceOutbox, type OutboxEntry, type RelayStatusBody } from '../../entities/outbox/model.ts';
import { AUTO_RETRY_LIMIT, classifyFailure, nextRetryDelayMs } from '../../entities/outbox/retry.ts';
import { sampleDraft } from './samples.ts';

const at = '2026-11-04T00:00:00.000Z';
const consent = { consentVersion: 'transfer-consent-v1', dimensionsConsent: true as const, photoConsent: false };
const entry = (): OutboxEntry => newEntry(sampleDraft(), consent, null, at);
const status = (state: RelayStatusBody['state'], extra: Partial<RelayStatusBody> = {}): RelayStatusBody => ({ pairingId: sampleDraft().pairingId, payloadHash: 'a'.repeat(64), state, receivedAt: at, expiresAt: at, ...extra });
const accepted = { result: 'VEHICLE_ACCEPTED' as const, acceptedItemId: 'b'.repeat(8), acceptedRevision: 7 };

test('receipt alone is received, never vehicle accepted', () => {
  const sent = reduceOutbox(reduceOutbox(entry(), { type: 'SEND_STARTED', at }), { type: 'STATUS', at, body: status('PENDING') });
  assert.equal(sent.status, 'received');
  assert.equal(sent.terminalAck, null);
  assert.equal(confirmationOf(sent), null);
});

test('state ACCEPTED without the ack body does not show accepted', () => {
  const next = reduceOutbox(entry(), { type: 'STATUS', at, body: status('ACCEPTED') });
  assert.equal(next.status, 'received');
});

test('late PENDING and duplicates cannot undo a terminal ack (monotonic)', () => {
  const done = reduceOutbox(entry(), { type: 'STATUS', at, body: status('ACCEPTED', { terminalAck: accepted }) });
  assert.equal(done.status, 'accepted');
  for (const event of [
    { type: 'STATUS', at, body: status('PENDING') },
    { type: 'STATUS', at, body: status('CONFLICT_REPORTED', { conflict: { authoritativeItemId: 'x', authoritativeRevision: 3 } }) },
    { type: 'RELAY_ERROR', at, code: 'PAIRING_REVOKED' },
    { type: 'REPACK', at, pairingId: 'other' },
    { type: 'SEND_STARTED', at },
  ] as const) {
    assert.deepEqual(reduceOutbox(done, event), done);
  }
  assert.equal(confirmationOf(done)?.acceptedRevision, 7);
});

test('late PENDING cannot move a conflict backwards', () => {
  const conflict = reduceOutbox(entry(), { type: 'STATUS', at, body: status('CONFLICT_REPORTED', { conflict: { authoritativeItemId: 'x', authoritativeRevision: 9 } }) });
  assert.equal(conflict.status, 'conflict');
  assert.equal(reduceOutbox(conflict, { type: 'STATUS', at, body: status('PENDING') }).status, 'conflict');
});

test('after a receipt, an unreadable ack is unknown, neither success nor failure', () => {
  const received = reduceOutbox(entry(), { type: 'STATUS', at, body: status('PENDING') });
  for (const code of ['PAIRING_REVOKED', 'CREDENTIAL_EXPIRED', 'UNAUTHENTICATED', 'DRAFT_NOT_FOUND']) {
    assert.equal(reduceOutbox(received, { type: 'RELAY_ERROR', at, code }).status, 'unknown', code);
  }
});

test('before any receipt a revoked or expired credential ends the entry but keeps it', () => {
  assert.equal(reduceOutbox(entry(), { type: 'RELAY_ERROR', at, code: 'PAIRING_REVOKED' }).status, 'revoked');
  assert.equal(reduceOutbox(entry(), { type: 'RELAY_ERROR', at, code: 'CREDENTIAL_EXPIRED' }).status, 'expired');
});

test('reload turns sending back into pending; the same key is resent', () => {
  const sending = reduceOutbox(entry(), { type: 'SEND_STARTED', at });
  const recovered = reduceOutbox(sending, { type: 'RECOVER_AFTER_RELOAD', at });
  assert.equal(recovered.status, 'pending');
  assert.deepEqual(recovered.draft, sending.draft);
});

test('repack changes only the pairing id and never moves a decided entry', () => {
  const lonely = newEntry({ ...sampleDraft(), pairingId: NO_PAIRING_ID }, consent, null, at);
  assert.equal(canRepack(lonely, 'new'), true);
  const moved = reduceOutbox(lonely, { type: 'REPACK', at, pairingId: 'new' });
  assert.equal(moved.draft.pairingId, 'new');
  assert.deepEqual(moved.draft.payload, lonely.draft.payload);
  const decided = reduceOutbox(entry(), { type: 'STATUS', at, body: status('ACCEPTED', { terminalAck: accepted }) });
  assert.equal(canRepack(decided, 'new'), false);
});

test('repacking an unknown entry stays marked as possibly already applied', () => {
  const received = reduceOutbox(entry(), { type: 'STATUS', at, body: status('PENDING') });
  const unknown = reduceOutbox(received, { type: 'RELAY_ERROR', at, code: 'UNAUTHENTICATED' });
  const moved = reduceOutbox(unknown, { type: 'REPACK', at, pairingId: 'new' });
  assert.equal(moved.status, 'pending');
  assert.equal(moved.resentAfterUnknown, true);
});

test('replacing a never-sent revision ends it; a received one keeps being read', () => {
  assert.equal(reduceOutbox(entry(), { type: 'REPLACED_LOCALLY', at, clientRevision: 2 }).status, 'superseded');
  const received = reduceOutbox(entry(), { type: 'STATUS', at, body: status('PENDING') });
  const replaced = reduceOutbox(received, { type: 'REPLACED_LOCALLY', at, clientRevision: 2 });
  assert.equal(replaced.status, 'received');
  assert.equal(replaced.replacedBy, 2);
  assert.equal(reduceOutbox(replaced, { type: 'STATUS', at, body: status('ACCEPTED', { terminalAck: accepted }) }).status, 'accepted');
});

test('terminal policy errors never retry automatically; connection and 5xx do, with a bound', () => {
  assert.deepEqual(classifyFailure({ kind: 'network' }), { action: 'retry', kind: 'NETWORK', code: 'CONNECTION_FAILED' });
  assert.equal(classifyFailure({ kind: 'http', status: 503, code: 'DATABASE_UNAVAILABLE', details: {} }).action, 'retry');
  assert.equal(classifyFailure({ kind: 'http', status: 429, code: 'RATE_LIMITED', details: {} }).action, 'retry');
  for (const code of ['DRAFT_CONTENT_CONFLICT', 'STALE_CLIENT_REVISION', 'PAIRING_DRAFT_LIMIT', 'DRAFT_SCHEMA_INVALID', 'CONSENT_VERSION_UNSUPPORTED']) {
    assert.equal(classifyFailure({ kind: 'http', status: 409, code, details: {} }).action, 'stop', code);
  }
  assert.equal(nextRetryDelayMs('NETWORK', AUTO_RETRY_LIMIT), null);
  assert.ok((nextRetryDelayMs('NETWORK', 1) as number) < (nextRetryDelayMs('NETWORK', 3) as number));
});
