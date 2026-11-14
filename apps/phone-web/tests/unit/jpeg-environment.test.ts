import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { readRelayBaseUrl } from '../../shared/config/relay-url.ts';
import { isCleanJpeg, stripJpegMetadata } from '../../shared/lib/jpeg-structure.ts';
import { judgeEnvironment } from '../../shared/security/environment.ts';

const realJpeg = new Uint8Array(readFileSync(fileURLToPath(new URL('../../../../services/relay/test/fixtures/canvas-chrome.jpg', import.meta.url))));

function segment(marker: number, text: string): number[] {
  const body = [...Buffer.from(text, 'latin1')];
  const length = body.length + 2;
  return [0xff, marker, length >> 8, length & 0xff, ...body];
}
const inject = (extra: number[]) => new Uint8Array([0xff, 0xd8, ...extra, ...realJpeg.subarray(2)]);

test('a canvas JPEG from Chrome is clean as it stands', () => {
  assert.equal(isCleanJpeg(realJpeg), true);
});

test('EXIF, comments and unknown APP segments are detected and stripped; the image data stays identical', () => {
  const dirty = inject([...segment(0xe1, 'Exif\0\0GPSLatitude'), ...segment(0xfe, 'secret comment'), ...segment(0xe5, 'vendor')]);
  assert.equal(isCleanJpeg(dirty), false);
  const clean = stripJpegMetadata(dirty) as Uint8Array;
  assert.equal(isCleanJpeg(clean), true);
  assert.equal(Buffer.from(clean).includes('GPSLatitude'), false);
  assert.equal(Buffer.from(clean).includes('secret comment'), false);
  assert.deepEqual(Buffer.from(clean), Buffer.from(realJpeg));
});

test('an allowed APP marker with the wrong identifier is not allowed', () => {
  const spoofed = inject(segment(0xe0, 'NOTJFIF\0hidden'));
  assert.equal(isCleanJpeg(spoofed), false);
  assert.equal(isCleanJpeg(stripJpegMetadata(spoofed) as Uint8Array), true);
});

test('non-JPEG and truncated input are refused', () => {
  assert.equal(stripJpegMetadata(new Uint8Array([1, 2, 3, 4])), null);
  assert.equal(isCleanJpeg(realJpeg.subarray(0, realJpeg.length - 2)), false);
});

test('relay URL policy: https anywhere, http on loopback only, nothing secret or malformed', () => {
  assert.deepEqual(readRelayBaseUrl('https://relay.example/'), { ok: true, url: 'https://relay.example' });
  assert.deepEqual(readRelayBaseUrl('http://127.0.0.1:43002'), { ok: true, url: 'http://127.0.0.1:43002' });
  assert.deepEqual(readRelayBaseUrl('http://192.168.0.5:43002'), { ok: false, reason: 'CLEARTEXT_NON_LOOPBACK' });
  assert.deepEqual(readRelayBaseUrl('https://user:pw@relay.example'), { ok: false, reason: 'INVALID' });
  assert.deepEqual(readRelayBaseUrl(undefined), { ok: false, reason: 'MISSING' });
});

test('environment gates each report their own reason', () => {
  const good = { isSecureContext: true, hasSubtleCrypto: true, hasIndexedDb: true, pageProtocol: 'https:', relay: { ok: true as const, url: 'https://relay.example' } };
  assert.deepEqual(judgeEnvironment(good), []);
  assert.deepEqual(judgeEnvironment({ ...good, isSecureContext: false, hasSubtleCrypto: false }), ['INSECURE_CONTEXT', 'WEBCRYPTO_MISSING']);
  assert.deepEqual(judgeEnvironment({ ...good, hasIndexedDb: false }), ['INDEXEDDB_MISSING']);
  assert.deepEqual(judgeEnvironment({ ...good, relay: { ok: false, reason: 'CLEARTEXT_NON_LOOPBACK' } }), ['RELAY_CLEARTEXT']);
  assert.deepEqual(judgeEnvironment({ ...good, relay: { ok: true, url: 'http://10.0.0.5:1' } }), ['MIXED_CONTENT']);
});
