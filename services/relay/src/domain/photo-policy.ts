// Header-level inspection of already-resized photos. It does not decode pixels and never re-encodes: the phone
// client owns resize/EXIF removal, the relay refuses what visibly violates the minimal-photo policy.
import { RelayError } from './errors.ts';

export interface PhotoLimits { photoDecodedBytes: number; photoMaxDimensionPx: number; photoMimeAllowlist: string[] }
export interface PhotoInfo { width: number; height: number }

const malformed = () => new RelayError(422, 'PHOTO_MALFORMED');
const metadata = (kind: string) => new RelayError(422, 'PHOTO_METADATA_FORBIDDEN', { metadata: kind });

// Baseline, extended and progressive DCT only; anything else is not what an image encoder for this flow emits.
const START_OF_FRAME = new Set([0xc0, 0xc1, 0xc2]);
const SOS = 0xda;
const EOI = 0xd9;
const COM = 0xfe;
const isApp = (marker: number) => marker >= 0xe0 && marker <= 0xef;
const isRestart = (marker: number) => marker >= 0xd0 && marker <= 0xd7;
// DHT, DQT, DRI: tables that may legally sit before a frame and between progressive scans.
const TABLE_MARKERS = new Set([0xc4, 0xdb, 0xdd]);
// Each allowed APP segment must carry its known identifier: the marker number alone would let EXIF or a thumbnail in.
const ALLOWED_APP_IDENTIFIER = new Map([[0xe0, 'JFIF\0'], [0xe2, 'ICC_PROFILE\0'], [0xee, 'Adobe']]);

/** Next marker position after entropy-coded data, or -1 if the data runs off the end. */
function endOfEntropyData(b: Buffer, start: number): number {
  let offset = start;
  while (offset + 1 < b.length) {
    if (b[offset] !== 0xff) {
      offset += 1;
      continue;
    }
    const next = b[offset + 1] as number;
    if (next === 0x00 || isRestart(next)) offset += 2; // stuffed byte or restart: still scan data
    else if (next === 0xff) offset += 1; // fill byte
    else return offset;
  }
  return -1;
}

function assertAllowedApp(b: Buffer, marker: number, dataStart: number, dataEnd: number, afterFirstScan: boolean): void {
  const identifier = ALLOWED_APP_IDENTIFIER.get(marker);
  if (afterFirstScan || identifier === undefined) throw metadata(marker === 0xe1 ? 'JPEG_APP1' : 'JPEG_APP');
  if (b.toString('latin1', dataStart, Math.min(dataEnd, dataStart + identifier.length)) !== identifier) throw metadata('JPEG_APP');
}

/** Walks every marker from SOI to the single final EOI, so metadata cannot hide behind or between scans or after the image. */
function inspectJpeg(b: Buffer): PhotoInfo {
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) throw malformed();
  let offset = 2;
  let info: PhotoInfo | null = null;
  let scans = 0;
  while (offset + 2 <= b.length) {
    if (b[offset] !== 0xff) throw malformed();
    while (b[offset + 1] === 0xff) offset += 1;
    const marker = b[offset + 1];
    if (marker === undefined) throw malformed();
    if (marker === EOI) {
      if (offset + 2 !== b.length || !info || scans === 0) throw malformed(); // trailing bytes could hold anything
      return info;
    }
    if (offset + 4 > b.length) throw malformed();
    const length = b.readUInt16BE(offset + 2);
    const dataStart = offset + 4;
    const dataEnd = offset + 2 + length;
    if (length < 2 || dataEnd > b.length) throw malformed();

    if (marker === COM) throw metadata('JPEG_COMMENT');
    if (isApp(marker)) {
      assertAllowedApp(b, marker, dataStart, dataEnd, scans > 0);
    } else if (START_OF_FRAME.has(marker)) {
      if (info || scans > 0 || length < 7) throw malformed();
      info = { height: b.readUInt16BE(offset + 5), width: b.readUInt16BE(offset + 7) };
    } else if (marker === SOS) {
      if (!info) throw malformed();
      scans += 1;
      offset = endOfEntropyData(b, dataEnd);
      if (offset < 0) throw malformed();
      continue;
    } else if (!TABLE_MARKERS.has(marker)) {
      throw malformed();
    }
    offset = dataEnd;
  }
  throw malformed();
}

const ALLOWED_WEBP_CHUNKS = new Set(['VP8 ', 'VP8L', 'VP8X', 'ALPH', 'ICCP']);

function inspectWebp(b: Buffer): PhotoInfo {
  if (b.length < 20 || b.toString('latin1', 0, 4) !== 'RIFF' || b.toString('latin1', 8, 12) !== 'WEBP') throw malformed();
  if (b.readUInt32LE(4) + 8 !== b.length) throw malformed();
  let offset = 12;
  let info: PhotoInfo | null = null;
  while (offset + 8 <= b.length) {
    const type = b.toString('latin1', offset, offset + 4);
    const size = b.readUInt32LE(offset + 4);
    const data = offset + 8;
    if (data + size > b.length) throw malformed();
    if (type === 'EXIF' || type === 'XMP ') throw metadata(`WEBP_${type.trim()}`);
    if (type === 'ANIM' || type === 'ANMF') throw new RelayError(422, 'PHOTO_TYPE_FORBIDDEN', { reason: 'ANIMATED' });
    if (!ALLOWED_WEBP_CHUNKS.has(type)) throw metadata('WEBP_UNKNOWN_CHUNK');
    if (type === 'VP8X' && size >= 10) {
      info = { width: b.readUIntLE(data + 4, 3) + 1, height: b.readUIntLE(data + 7, 3) + 1 };
    } else if (type === 'VP8 ' && size >= 10 && !info) {
      if (b[data + 3] !== 0x9d || b[data + 4] !== 0x01 || b[data + 5] !== 0x2a) throw malformed();
      info = { width: b.readUInt16LE(data + 6) & 0x3fff, height: b.readUInt16LE(data + 8) & 0x3fff };
    } else if (type === 'VP8L' && size >= 5 && !info) {
      if (b[data] !== 0x2f) throw malformed();
      const bits = b.readUInt32LE(data + 1);
      info = { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
    }
    offset = data + size + (size % 2);
  }
  if (!info || offset < b.length) throw malformed(); // leftover shorter than a chunk header
  return info;
}

export function inspectPhoto(mime: string, bytes: Buffer, limits: PhotoLimits): PhotoInfo {
  if (!limits.photoMimeAllowlist.includes(mime)) throw new RelayError(415, 'PHOTO_TYPE_FORBIDDEN', { reason: 'MIME' });
  if (bytes.length === 0) throw malformed();
  if (bytes.length > limits.photoDecodedBytes) throw new RelayError(413, 'PHOTO_TOO_LARGE', { maxBytes: limits.photoDecodedBytes });
  const info = mime === 'image/jpeg' ? inspectJpeg(bytes) : inspectWebp(bytes);
  if (info.width < 1 || info.height < 1) throw malformed();
  if (info.width > limits.photoMaxDimensionPx || info.height > limits.photoMaxDimensionPx) {
    throw new RelayError(422, 'PHOTO_DIMENSIONS_TOO_LARGE', { maxDimensionPx: limits.photoMaxDimensionPx });
  }
  return info;
}
