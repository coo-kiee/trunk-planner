// Pairing lifecycle: create (vehicle), single-use claim (phone), status, revoke (either side).
import { randomUUID } from 'node:crypto';
import type { RelayConfig } from '../config.ts';
import { RelayError } from '../domain/errors.ts';
import { digestToken, issueToken } from '../domain/tokens.ts';
import { inTransaction, lockPairing, timeAfterLock, type Db } from './pool.ts';

// Serializes the global active-pairing cap check. Arbitrary fixed key owned by this service.
const ACTIVE_PAIRING_CAP_LOCK = 7_203_113;

export interface CreatedPairing {
  pairingId: string;
  pairingToken: string;
  vehicleToken: string;
  claimExpiresAt: Date;
  credentialExpiresAt: Date;
}

export async function createPairing(db: Db, config: RelayConfig): Promise<CreatedPairing> {
  return inTransaction(db, async (tx) => {
    await tx.query('SELECT pg_advisory_xact_lock($1)', [ACTIVE_PAIRING_CAP_LOCK]);
    const active = await tx.query<{ count: number }>(
      `SELECT count(*) AS count FROM relay_pairing WHERE revoked_at IS NULL AND credential_expires_at > clock_timestamp()`,
    );
    if ((active.rows[0]?.count ?? 0) >= config.limits.maxActivePairings) {
      throw new RelayError(429, 'ACTIVE_PAIRING_LIMIT', {}, 60);
    }
    const pairingId = randomUUID();
    const pairing = issueToken('PAIRING');
    const vehicle = issueToken('VEHICLE');
    const inserted = await tx.query<{ claim_expires_at: Date }>(
      `INSERT INTO relay_pairing (pairing_id, configuration_version, created_at, claim_expires_at, credential_expires_at)
       SELECT $1, version, t.now, t.now + make_interval(secs => pairing_ttl_seconds), t.now + make_interval(secs => pairing_ttl_seconds)
         FROM relay_configuration, (SELECT clock_timestamp() AS now) t WHERE version = $2
       RETURNING claim_expires_at`,
      [pairingId, config.relayConfigVersion],
    );
    const claimExpiresAt = inserted.rows[0]?.claim_expires_at;
    if (!claimExpiresAt) throw new Error('configuration version missing from database');
    await tx.query(
      `INSERT INTO relay_credential (token_digest, pairing_id, role, created_at)
       VALUES ($1, $3, 'PAIRING', clock_timestamp()), ($2, $3, 'VEHICLE', clock_timestamp())`,
      [pairing.digest, vehicle.digest, pairingId],
    );
    return { pairingId, pairingToken: pairing.token, vehicleToken: vehicle.token, claimExpiresAt, credentialExpiresAt: claimExpiresAt };
  });
}

export interface ClaimedPairing { pairingId: string; phoneToken: string; credentialExpiresAt: Date }

/**
 * Consumes the QR token exactly once. A lost response cannot be retried by design: no secret is stored to re-issue,
 * so the retry sees PAIRING_TOKEN_CONSUMED and the user scans a fresh QR.
 */
export async function claimPairing(db: Db, pairingToken: string): Promise<ClaimedPairing> {
  return inTransaction(db, async (tx) => {
    const digest = digestToken(pairingToken);
    const found = await tx.query<{ pairing_id: string; role: string }>(
      'SELECT pairing_id, role FROM relay_credential WHERE token_digest = $1',
      [digest],
    );
    const credential = found.rows[0];
    if (!credential) throw new RelayError(401, 'UNAUTHENTICATED');
    if (credential.role !== 'PAIRING') throw new RelayError(403, 'ROLE_FORBIDDEN');
    const locked = await tx.query<{
      revoked_at: Date | null; claimed_at: Date | null; claim_expires_at: Date; credential_ttl_seconds: number;
    }>(
      `SELECT p.revoked_at, p.claimed_at, p.claim_expires_at, c.credential_ttl_seconds
         FROM relay_pairing p JOIN relay_configuration c ON c.version = p.configuration_version
        WHERE p.pairing_id = $1 FOR UPDATE OF p`,
      [credential.pairing_id],
    );
    const pairing = locked.rows[0];
    if (!pairing) throw new RelayError(401, 'UNAUTHENTICATED');
    if (pairing.revoked_at) throw new RelayError(403, 'PAIRING_REVOKED');
    if (pairing.claimed_at) throw new RelayError(409, 'PAIRING_TOKEN_CONSUMED');
    if (pairing.claim_expires_at <= (await timeAfterLock(tx))) throw new RelayError(410, 'PAIRING_EXPIRED');

    const phone = issueToken('PHONE');
    const updated = await tx.query<{ credential_expires_at: Date }>(
      `UPDATE relay_pairing
          SET claimed_at = clock_timestamp(), credential_expires_at = clock_timestamp() + make_interval(secs => $2::int)
        WHERE pairing_id = $1 AND claimed_at IS NULL AND revoked_at IS NULL AND claim_expires_at > clock_timestamp()
        RETURNING credential_expires_at`,
      [credential.pairing_id, pairing.credential_ttl_seconds],
    );
    // Validity is part of the write itself, so expiry between the check above and this statement issues nothing.
    if (updated.rowCount !== 1) throw new RelayError(410, 'PAIRING_EXPIRED');
    await tx.query('UPDATE relay_credential SET consumed_at = clock_timestamp() WHERE token_digest = $1', [digest]);
    await tx.query(
      `INSERT INTO relay_credential (token_digest, pairing_id, role, created_at) VALUES ($1, $2, 'PHONE', clock_timestamp())`,
      [phone.digest, credential.pairing_id],
    );
    return { pairingId: credential.pairing_id, phoneToken: phone.token, credentialExpiresAt: updated.rows[0]!.credential_expires_at };
  });
}

export async function readPairingStatus(db: Db, vehicleToken: string) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, vehicleToken, ['VEHICLE'], 'SHARE');
    return {
      pairingId: ctx.pairingId,
      state: ctx.claimedAt ? 'CLAIMED' : 'WAITING_CLAIM',
      claimExpiresAt: ctx.claimExpiresAt.toISOString(),
      credentialExpiresAt: ctx.credentialExpiresAt.toISOString(),
    };
  });
}

/** Blocks all further access, purges content of active drafts; durable acks and tombstones are left to their own TTL. */
export async function revokePairing(db: Db, token: string, role: 'VEHICLE' | 'PHONE'): Promise<{ pairingId: string; purgedDrafts: number }> {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, [role], 'UPDATE');
    await tx.query('UPDATE relay_pairing SET revoked_at = clock_timestamp() WHERE pairing_id = $1', [ctx.pairingId]);
    const purged = await tx.query(
      `UPDATE relay_draft SET state = 'REVOKED', payload = NULL, terminal_at = clock_timestamp()
        WHERE pairing_id = $1 AND state IN ('PENDING', 'CONFLICT_REPORTED')`,
      [ctx.pairingId],
    );
    return { pairingId: ctx.pairingId, purgedDrafts: purged.rowCount ?? 0 };
  });
}
