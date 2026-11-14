import assert from 'node:assert/strict';
import { test } from 'node:test';
import { newEditor } from '../../entities/item/model.ts';
import { newEntry, type OutboxEntry } from '../../entities/outbox/model.ts';
import { isConfirmedItem, isOutboxEntry, isPairingRecord, isStoredEditor } from '../../shared/storage/integrity.ts';
import { sampleDraft } from './samples.ts';

const at = '2026-11-04T00:00:00.000Z';
const consent = { consentVersion: 'transfer-consent-v1', dimensionsConsent: true as const, photoConsent: false };
const good = (): OutboxEntry => newEntry(sampleDraft(), consent, null, at);
const damaged = (change: (entry: any) => void) => { const copy = structuredClone(good()) as any; change(copy); return copy; };

test('an intact outbox entry is readable', () => {
  assert.equal(isOutboxEntry(good()), true);
});

test('partial damage anywhere in an outbox entry makes it unreadable', () => {
  const cases: Record<string, (entry: any) => void> = {
    'missing dimensions': (entry) => { delete entry.draft.payload.measuredDimensionsMm; },
    'missing proxy': (entry) => { delete entry.draft.payload.shapeProxy; },
    'unknown status': (entry) => { entry.status = 'nonsense'; },
    'key disagrees with draft': (entry) => { entry.key = 'other:1'; },
    'missing consent': (entry) => { delete entry.consent; },
    'receipt wrong type': (entry) => { entry.receipt = 'yes'; },
    'accepted without ack': (entry) => { entry.status = 'accepted'; },
    'conflict without numbers': (entry) => { entry.status = 'conflict'; },
    'ack with unknown reject code': (entry) => { entry.terminalAck = { result: 'VEHICLE_REJECTED', rejectCode: 'X' }; },
    'attempts not a number': (entry) => { entry.attempts = '1'; },
  };
  for (const [name, change] of Object.entries(cases)) assert.equal(isOutboxEntry(damaged(change)), false, name);
  assert.equal(isOutboxEntry(null), false);
});

test('editor, pairing and confirmation records are checked whole', () => {
  const editor = newEditor('id', at);
  assert.equal(isStoredEditor({ saveId: 's', state: editor }), true);
  assert.equal(isStoredEditor({ state: editor }), false, 'no save id');
  assert.equal(isStoredEditor({ saveId: 's', state: { ...editor, dimX: 400 } }), false, 'number instead of text');
  assert.equal(isStoredEditor({ saveId: 's', state: { ...editor, shapeKind: 'SPHERE' } }), false, 'unknown shape');
  const pairing = { pairingId: 'p', phoneToken: 't', credentialExpiresAt: at, consentVersions: ['v'], relayConfigVersion: 1, claimedAt: at, state: 'ACTIVE' };
  assert.equal(isPairingRecord(pairing), true);
  assert.equal(isPairingRecord({ ...pairing, phoneToken: 5 }), false);
  assert.equal(isPairingRecord({ ...pairing, state: 'MAYBE' }), false);
  const confirmed = { sentItemId: 'a', acceptedItemId: 'b', acceptedRevision: 1, draftKey: 'k', confirmedAt: at };
  assert.equal(isConfirmedItem(confirmed), true);
  assert.equal(isConfirmedItem({ ...confirmed, acceptedRevision: '1' }), false);
});
