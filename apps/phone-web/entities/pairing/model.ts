// The phone's local record of one claimed pairing. The bearer lives only in this IndexedDB record and the Authorization header.
export interface PairingRecord {
  pairingId: string;
  phoneToken: string;
  credentialExpiresAt: string;
  consentVersions: string[];
  relayConfigVersion: number;
  claimedAt: string;
  /** ACTIVE until the relay or the user says otherwise. The clock alone can also move it to EXPIRED for display. */
  state: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
}

export type PairingView =
  | { kind: 'NONE' }
  | { kind: 'ACTIVE'; record: PairingRecord }
  | { kind: 'EXPIRED'; record: PairingRecord }
  | { kind: 'REVOKED'; record: PairingRecord };

export function viewPairing(record: PairingRecord | null, nowMs: number): PairingView {
  if (!record) return { kind: 'NONE' };
  if (record.state === 'REVOKED') return { kind: 'REVOKED', record };
  if (record.state === 'EXPIRED' || Date.parse(record.credentialExpiresAt) <= nowMs) return { kind: 'EXPIRED', record };
  return { kind: 'ACTIVE', record };
}

const QR_TOKEN_SHAPE = /^qp_[A-Za-z0-9_-]{43}$/;
export const isQrTokenShaped = (value: string): boolean => QR_TOKEN_SHAPE.test(value);
