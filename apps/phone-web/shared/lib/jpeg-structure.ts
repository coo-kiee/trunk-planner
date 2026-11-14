// Client mirror of the relay's JPEG allowlist: strips what the relay would refuse and proves the result passes.
// Some browsers' canvas encoders add APP segments, so stripping happens instead of trusting the encoder.
const SOS = 0xda;
const EOI = 0xd9;
const COM = 0xfe;
const ALLOWED_APP: ReadonlyMap<number, string> = new Map([[0xe0, 'JFIF\0'], [0xe2, 'ICC_PROFILE\0'], [0xee, 'Adobe']]);
const isApp = (marker: number) => marker >= 0xe0 && marker <= 0xef;

function hasIdentifier(bytes: Uint8Array, start: number, end: number, identifier: string): boolean {
  if (end - start < identifier.length) return false;
  for (let index = 0; index < identifier.length; index += 1) {
    if (bytes[start + index] !== identifier.charCodeAt(index)) return false;
  }
  return true;
}

/** Removes every APP or comment segment outside the allowlist from the header. Returns null if the structure is not JPEG. */
export function stripJpegMetadata(bytes: Uint8Array): Uint8Array | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  const parts: Uint8Array[] = [bytes.subarray(0, 2)];
  let offset = 2;
  for (;;) {
    if (offset + 4 > bytes.length || bytes[offset] !== 0xff) return null;
    const marker = bytes[offset + 1] as number;
    const length = ((bytes[offset + 2] as number) << 8) | (bytes[offset + 3] as number);
    const end = offset + 2 + length;
    if (length < 2 || end > bytes.length) return null;
    if (marker === SOS) {
      parts.push(bytes.subarray(offset));
      break;
    }
    const identifier = ALLOWED_APP.get(marker);
    const drop = marker === COM || (isApp(marker) && !(identifier && hasIdentifier(bytes, offset + 4, end, identifier)));
    if (!drop) parts.push(bytes.subarray(offset, end));
    offset = end;
  }
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const output = new Uint8Array(total);
  let position = 0;
  for (const part of parts) {
    output.set(part, position);
    position += part.length;
  }
  return output;
}

/** True only if no APP1/comment/unknown APP survives before the first scan and the file ends exactly at EOI. */
export function isCleanJpeg(bytes: Uint8Array): boolean {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return false;
  if (bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== EOI) return false;
  let offset = 2;
  while (offset + 4 <= bytes.length) {
    if (bytes[offset] !== 0xff) return false;
    const marker = bytes[offset + 1] as number;
    if (marker === SOS) return true;
    const length = ((bytes[offset + 2] as number) << 8) | (bytes[offset + 3] as number);
    const end = offset + 2 + length;
    if (length < 2 || end > bytes.length) return false;
    if (marker === COM) return false;
    if (isApp(marker)) {
      const identifier = ALLOWED_APP.get(marker);
      if (!identifier || !hasIdentifier(bytes, offset + 4, end, identifier)) return false;
    }
    offset = end;
  }
  return false;
}
