// Outbox records for every semantic state, in the app's real stored shape, so the DS check can render each one.
const uuid = (index) => `${String(index).padStart(8, '0')}-1111-4111-8111-111111111111`;
const PAIRING_ID = '99999999-1111-4111-8111-111111111111';

export const SEMANTIC_STATES = [
  { state: 'pending', token: '--cargo-color-unverified' },
  { state: 'sending', token: '--cargo-color-accent' },
  { state: 'received', token: '--cargo-color-uncertain' },
  { state: 'conflict', token: '--cargo-color-conflict' },
  { state: 'accepted', token: '--cargo-color-clear' },
  { state: 'rejected', token: '--cargo-color-stale' },
  { state: 'superseded', token: '--cargo-color-stale' },
  { state: 'expired', token: '--cargo-color-stale' },
  { state: 'revoked', token: '--cargo-color-stale' },
  { state: 'unknown', token: '--cargo-color-unverified' },
  { state: 'blocked', token: '--cargo-color-conflict' },
];

function entry(index, status, patch = {}) {
  const at = new Date(Date.now() - 60_000 + index * 1000).toISOString(); // In the past, so seeded pending entries are due now.
  const draftId = uuid(index);
  return {
    key: `${draftId}:1`,
    draft: {
      draftId, pairingId: PAIRING_ID, schemaVersion: 1, clientRevision: 1,
      payload: {
        schemaVersion: 1, id: uuid(100 + index), revision: 1, createdAt: at, updatedAt: at, name: `상태 ${status}`, shapeKind: 'BOX',
        shapeProxy: [{ origin: { x: 0, y: 0, z: 0 }, size: { x: 100, y: 100, z: 100 } }],
        measuredDimensionsMm: { x: 100, y: 100, z: 100 }, errorMm: 1, measurementSource: 'USER_TAPE', rotationRule: 'ANY24', stackable: true, removalPriority: 1, confirmedDimensions: true,
      },
    },
    consent: { consentVersion: 'transfer-consent-v1', dimensionsConsent: true, photoConsent: false },
    photoId: null, status, createdAt: at, updatedAt: at, receipt: null, terminalAck: null, conflict: null, lastFailure: null,
    attempts: 0, nextAttemptAt: at, replacedBy: null, resentAfterUnknown: false, ...patch,
  };
}

const receipt = { pairingId: PAIRING_ID, payloadHash: 'a'.repeat(64), receivedAt: '2026-11-04T00:00:00.000Z', expiresAt: '2036-11-04T00:00:00.000Z' };

export function buildSeed() {
  const accepted = entry(5, 'accepted', { receipt, terminalAck: { result: 'VEHICLE_ACCEPTED', acceptedItemId: uuid(105), acceptedRevision: 7 } });
  return {
    pairing: { pairingId: PAIRING_ID, phoneToken: `ph_${'a'.repeat(43)}`, credentialExpiresAt: '2036-11-04T00:00:00.000Z', consentVersions: ['transfer-consent-v1'], relayConfigVersion: 1, claimedAt: '2026-11-04T00:00:00.000Z', state: 'ACTIVE' },
    entries: [
      entry(1, 'pending'),
      entry(2, 'pending'),
      entry(3, 'received', { receipt, nextAttemptAt: null }),
      entry(4, 'conflict', { receipt, conflict: { authoritativeItemId: uuid(104), authoritativeRevision: 9 }, nextAttemptAt: null }),
      accepted,
      entry(6, 'rejected', { receipt, terminalAck: { result: 'VEHICLE_REJECTED', rejectCode: 'USER_DISCARDED' }, nextAttemptAt: null }),
      entry(7, 'superseded', { receipt, nextAttemptAt: null }),
      entry(8, 'expired', { nextAttemptAt: null }),
      entry(9, 'revoked', { nextAttemptAt: null }),
      entry(10, 'unknown', { receipt, nextAttemptAt: null }),
      entry(11, 'blocked', { lastFailure: { kind: 'POLICY', code: 'DRAFT_CONTENT_CONFLICT', at: '2026-11-04T00:00:00.000Z' }, nextAttemptAt: null }),
    ],
    confirmed: [{ sentItemId: uuid(105), acceptedItemId: uuid(105), acceptedRevision: 7, draftKey: accepted.key, confirmedAt: '2026-11-04T00:00:00.000Z' }],
  };
}
