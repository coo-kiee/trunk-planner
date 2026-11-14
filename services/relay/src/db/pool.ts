// Runtime connection pool plus the transaction helper. Serializes per-pairing work through the pairing row lock.
import pg from 'pg';
import { RelayError } from '../domain/errors.ts';
import { digestToken, type TokenRole } from '../domain/tokens.ts';

export type Db = pg.Pool;
export type Tx = pg.PoolClient;

// Bigints stay within the contract's safe-integer revision range.
pg.types.setTypeParser(pg.types.builtins.INT8, Number);

export function createPool(databaseUrl: string, onError: (cause: Error) => void): Db {
  const pool = new pg.Pool({
    connectionString: databaseUrl,
    max: 10,
    connectionTimeoutMillis: 3000,
    statement_timeout: 10000,
    options: '-c lock_timeout=8000',
    application_name: 'cargo-relay',
  });
  pool.on('error', onError);
  return pool;
}

export async function inTransaction<T>(db: Db, work: (tx: Tx) => Promise<T>): Promise<T> {
  const tx = await db.connect();
  let broken: Error | undefined;
  // While checked out, pg-pool's own listener is detached. A server crash between queries would otherwise be
  // an unhandled 'error' event and take the whole relay down instead of failing this one request.
  const onConnectionLost = (cause: Error) => {
    broken = cause;
  };
  tx.on('error', onConnectionLost);
  try {
    await tx.query('BEGIN');
    const result = await work(tx);
    await tx.query('COMMIT');
    return result;
  } catch (cause) {
    await tx.query('ROLLBACK').catch((rollbackError: Error) => {
      broken = rollbackError;
    });
    throw cause;
  } finally {
    tx.removeListener('error', onConnectionLost);
    tx.release(broken);
  }
}

/** Cheap check before a large body is buffered: unknown or dead credentials never make the relay hold a photo. */
export async function assertCredentialActive(db: Db, token: string, roles: TokenRole[]): Promise<void> {
  await inTransaction(db, (tx) => lockPairing(tx, token, roles, 'SHARE'));
}

/**
 * Must be its own statement issued after the row lock is held. A clock_timestamp() in the locking SELECT's
 * own projection is computed before the lock wait, so it can be older than the real time the check runs at.
 */
export async function timeAfterLock(tx: Tx): Promise<Date> {
  const clock = await tx.query<{ db_now: Date }>('SELECT clock_timestamp() AS db_now');
  return clock.rows[0]!.db_now;
}

export interface PairingContext {
  pairingId: string;
  role: TokenRole;
  now: Date;
  credentialExpiresAt: Date;
  claimedAt: Date | null;
  claimExpiresAt: Date;
  ttl: { draft: number; ack: number };
}

/**
 * Authenticates a bearer and takes the pairing row lock, so revoke vs submit/ack is strictly ordered and the
 * revoked/expiry checks read the state after any concurrent writer committed. Unknown tokens get one uniform 401.
 */
export async function lockPairing(tx: Tx, token: string, roles: TokenRole[], lock: 'UPDATE' | 'SHARE'): Promise<PairingContext> {
  const credential = await tx.query<{ pairing_id: string; role: TokenRole }>(
    'SELECT pairing_id, role FROM relay_credential WHERE token_digest = $1',
    [digestToken(token)],
  );
  const row = credential.rows[0];
  if (!row) throw new RelayError(401, 'UNAUTHENTICATED');
  if (!roles.includes(row.role)) throw new RelayError(403, 'ROLE_FORBIDDEN');
  const pairing = await tx.query<{
    revoked_at: Date | null; credential_expires_at: Date; claimed_at: Date | null; claim_expires_at: Date;
    draft_ttl_seconds: number; ack_ttl_seconds: number;
  }>(
    `SELECT p.revoked_at, p.credential_expires_at, p.claimed_at, p.claim_expires_at,
            c.draft_ttl_seconds, c.ack_ttl_seconds
       FROM relay_pairing p JOIN relay_configuration c ON c.version = p.configuration_version
      WHERE p.pairing_id = $1 FOR ${lock} OF p`,
    [row.pairing_id],
  );
  const current = pairing.rows[0];
  if (!current) throw new RelayError(401, 'UNAUTHENTICATED');
  if (current.revoked_at) throw new RelayError(403, 'PAIRING_REVOKED');
  const now = await timeAfterLock(tx);
  if (current.credential_expires_at <= now) throw new RelayError(401, 'CREDENTIAL_EXPIRED');
  return {
    pairingId: row.pairing_id,
    role: row.role,
    now,
    credentialExpiresAt: current.credential_expires_at,
    claimedAt: current.claimed_at,
    claimExpiresAt: current.claim_expires_at,
    ttl: { draft: current.draft_ttl_seconds, ack: current.ack_ttl_seconds },
  };
}
