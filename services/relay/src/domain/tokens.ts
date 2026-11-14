// Role-scoped opaque bearer tokens: 256 bits from the OS CSPRNG, stored only as SHA-256 digests.
import { createHash, randomBytes } from 'node:crypto';

export type TokenRole = 'PAIRING' | 'VEHICLE' | 'PHONE';

const PREFIX: Record<TokenRole, string> = { PAIRING: 'qp_', VEHICLE: 'vh_', PHONE: 'ph_' };
const TOKEN_SHAPE = /^(qp|vh|ph)_[A-Za-z0-9_-]{43}$/;
export const TOKEN_IN_TEXT = /(qp|vh|ph)_[A-Za-z0-9_-]{43}/;

export function issueToken(role: TokenRole): { token: string; digest: Buffer } {
  const token = PREFIX[role] + randomBytes(32).toString('base64url');
  return { token, digest: digestToken(token) };
}

export function digestToken(token: string): Buffer {
  return createHash('sha256').update(token).digest();
}

export function isTokenShaped(value: string): boolean {
  return TOKEN_SHAPE.test(value);
}
