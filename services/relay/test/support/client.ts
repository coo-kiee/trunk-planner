// Minimal protocol client and valid-by-contract fixtures. All test data is synthetic.
import { randomUUID } from 'node:crypto';

export interface ApiResponse { status: number; body: any; headers: Headers }

export async function api(base: string, method: string, path: string, options: { token?: string; json?: unknown; headers?: Record<string, string> } = {}): Promise<ApiResponse> {
  const headers: Record<string, string> = { ...options.headers };
  if (options.token) headers.authorization = `Bearer ${options.token}`;
  if (options.json !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(base + path, {
    method,
    headers,
    body: options.json === undefined ? undefined : JSON.stringify(options.json),
  });
  const text = await response.text();
  let body: any = text;
  try { body = JSON.parse(text); } catch { /* non-JSON, e.g. photo bytes */ }
  return { status: response.status, body, headers: response.headers };
}

export interface Pair { pairingId: string; vehicleToken: string; pairingToken: string; phoneToken: string; created: any; claimed: any }

export function fragmentToken(qrUrl: string): string {
  return new URL(qrUrl).hash.replace('#pairing=', '');
}

export async function newPairing(base: string): Promise<Omit<Pair, 'phoneToken' | 'claimed'>> {
  const created = await api(base, 'POST', '/vehicle/v1/pairings');
  if (created.status !== 201) throw new Error(`create pairing ${created.status} ${JSON.stringify(created.body)}`);
  return { pairingId: created.body.pairingId, vehicleToken: created.body.vehicleToken, pairingToken: fragmentToken(created.body.qrUrl), created: created.body };
}

export async function pair(base: string): Promise<Pair> {
  const pairing = await newPairing(base);
  const claimed = await api(base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: pairing.pairingToken } });
  if (claimed.status !== 200) throw new Error(`claim ${claimed.status} ${JSON.stringify(claimed.body)}`);
  return { ...pairing, phoneToken: claimed.body.phoneToken, claimed: claimed.body };
}

export function makeItem(overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: 1,
    id: randomUUID(),
    revision: 1,
    createdAt: '2026-11-03T00:00:00Z',
    updatedAt: '2026-11-03T00:00:00Z',
    name: 'synthetic-box',
    shapeKind: 'BOX',
    shapeProxy: [{ origin: { x: 0, y: 0, z: 0 }, size: { x: 400, y: 300, z: 200 } }],
    measuredDimensionsMm: { x: 400, y: 300, z: 200 },
    errorMm: 5,
    measurementSource: 'USER_TAPE',
    rotationRule: 'KEEP_UPRIGHT',
    stackable: true,
    removalPriority: 1,
    confirmedDimensions: true,
    ...overrides,
  };
}

export function makeDraft(pairingId: string, overrides: { draftId?: string; clientRevision?: number; payload?: Record<string, unknown> } = {}) {
  return {
    draftId: overrides.draftId ?? randomUUID(),
    pairingId,
    schemaVersion: 1,
    clientRevision: overrides.clientRevision ?? 1,
    payload: makeItem(overrides.payload),
  };
}

export const CONSENT = { consentVersion: 'transfer-consent-v1', dimensionsConsent: true, photoConsent: false };
export const PHOTO_CONSENT = { ...CONSENT, photoConsent: true };

export const submit = (base: string, pairing: Pair, draft: unknown, consent: unknown = CONSENT, photo?: unknown) =>
  api(base, 'POST', '/phone/v1/drafts', { token: pairing.phoneToken, json: { consent, draft, ...(photo ? { photo } : {}) } });

export const ack = (base: string, pairing: Pair, draftId: string, rev: number, body: unknown) =>
  api(base, 'POST', `/vehicle/v1/drafts/${draftId}/${rev}/ack`, { token: pairing.vehicleToken, json: body });

export const acceptedAck = (hash: string, itemId: string, revision = 7, expectedState = 'PENDING') =>
  ({ ackVersion: 1, expectedState, payloadHash: hash, result: 'VEHICLE_ACCEPTED', acceptedItemId: itemId, acceptedRevision: revision });
export const conflictAck = (hash: string, itemId: string, revision = 9) =>
  ({ ackVersion: 1, expectedState: 'PENDING', payloadHash: hash, result: 'CONFLICT', authoritativeItemId: itemId, authoritativeRevision: revision });
export const rejectedAck = (hash: string, code = 'USER_KEPT_VEHICLE_VERSION', expectedState = 'PENDING') =>
  ({ ackVersion: 1, expectedState, payloadHash: hash, result: 'VEHICLE_REJECTED', rejectCode: code });

/** A structurally valid baseline JPEG (headers only) with chosen segments and dimensions. Not a viewable image. */
export function jpeg(options: { width?: number; height?: number; segments?: Buffer[]; sofless?: boolean; afterFirstScan?: Buffer[]; afterEoi?: Buffer } = {}): Buffer {
  const { width = 640, height = 480 } = options;
  const jfif = Buffer.from([0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 1, 1, 0, 0, 1, 0, 1, 0, 0]);
  const sof = Buffer.from([0xff, 0xc0, 0x00, 0x0b, 8, height >> 8, height & 255, width >> 8, width & 255, 1, 1, 0x11, 0]);
  const sos = Buffer.from([0xff, 0xda, 0x00, 0x08, 1, 1, 0, 0, 63, 0, 0x12, 0x34]);
  return Buffer.concat([Buffer.from([0xff, 0xd8]), jfif, ...(options.segments ?? []), ...(options.sofless ? [] : [sof]), sos, ...(options.afterFirstScan ?? []), Buffer.from([0xff, 0xd9]), options.afterEoi ?? Buffer.alloc(0)]);
}

/** Allowed APP2 segment of a chosen total size, used as harmless filler or a marked payload. */
export function iccSegment(content: Buffer | number): Buffer {
  const body = Buffer.concat([Buffer.from('ICC_PROFILE\0'), typeof content === 'number' ? Buffer.alloc(content, 0x41) : content]);
  const head = Buffer.from([0xff, 0xe2, 0, 0]);
  head.writeUInt16BE(body.length + 2, 2);
  return Buffer.concat([head, body]);
}

/** A second scan, placed between SOS and EOI through `afterFirstScan`. */
export const secondScan = () => Buffer.from([0xff, 0xc4, 0x00, 0x04, 0, 0, 0xff, 0xda, 0x00, 0x08, 1, 1, 0, 0, 63, 0, 0x56, 0x78]);

export const exifSegment = () => {
  const payload = Buffer.from('Exif\0\0GPS-SENTINEL');
  const head = Buffer.alloc(4);
  head[0] = 0xff; head[1] = 0xe1;
  head.writeUInt16BE(payload.length + 2, 2);
  return Buffer.concat([head, payload]);
};

function riff(chunks: Buffer[]): Buffer {
  const body = Buffer.concat([Buffer.from('WEBP'), ...chunks]);
  const head = Buffer.from('RIFF\0\0\0\0');
  head.writeUInt32LE(body.length, 4);
  return Buffer.concat([head, body]);
}
function chunk(type: string, data: Buffer): Buffer {
  const head = Buffer.alloc(8);
  head.write(type, 0, 'latin1');
  head.writeUInt32LE(data.length, 4);
  return Buffer.concat([head, data, data.length % 2 ? Buffer.alloc(1) : Buffer.alloc(0)]);
}
export function webp(options: { width?: number; height?: number; extra?: [string, Buffer][] } = {}): Buffer {
  const { width = 640, height = 480 } = options;
  const data = Buffer.alloc(10);
  data[3] = 0x9d; data[4] = 0x01; data[5] = 0x2a;
  data.writeUInt16LE(width, 6);
  data.writeUInt16LE(height, 8);
  return riff([chunk('VP8 ', data), ...(options.extra ?? []).map(([type, value]) => chunk(type, value))]);
}
