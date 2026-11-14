// RFC 8785 JCS + SHA-256: the same logical JSON hashes equally regardless of key order.
import { createHash } from 'node:crypto';
import canonicalizeModule from 'canonicalize';

// CommonJS function export whose bundled types describe a namespace.
const canonicalize = canonicalizeModule as unknown as (value: unknown) => string | undefined;

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

export function canonicalHash(value: unknown): string {
  const text = canonicalize(value);
  if (text === undefined) throw new Error('value is not canonicalizable');
  return sha256Hex(text);
}
