import type { ItemDraft, ItemVersion } from '@cargo/contracts';

export const sampleItem = (): ItemVersion => ({
  schemaVersion: 1,
  id: '12345678-1234-4234-8234-123456789abc',
  revision: 1,
  createdAt: '2026-11-03T00:00:00.000Z',
  updatedAt: '2026-11-03T00:00:00.000Z',
  name: '시험 상자',
  shapeKind: 'BOX',
  shapeProxy: [{ origin: { x: 0, y: 0, z: 0 }, size: { x: 400, y: 300, z: 200 } }],
  measuredDimensionsMm: { x: 400, y: 300, z: 200 },
  errorMm: 5,
  measurementSource: 'USER_TAPE',
  rotationRule: 'KEEP_UPRIGHT',
  stackable: true,
  removalPriority: 1,
  confirmedDimensions: true,
});

export const sampleDraft = (): ItemDraft => ({
  draftId: '22345678-1234-4234-8234-123456789abc',
  pairingId: '32345678-1234-4234-8234-123456789abc',
  schemaVersion: 1,
  payload: sampleItem(),
  clientRevision: 1,
});
