// Idempotent TTL sweep. SKIP LOCKED keeps it from blocking requests and lets several runners coexist.
// Queries already treat lapsed rows as expired, so a late cleanup only delays physical removal, never correctness.
import type { Db } from './pool.ts';

export interface CleanupResult { expiredDrafts: number; deletedAcks: number; deletedPairings: number }

export async function runCleanup(db: Db, batchSize: number): Promise<CleanupResult> {
  const expired = await db.query(
    `UPDATE relay_draft SET state = 'EXPIRED', payload = NULL, terminal_at = clock_timestamp()
      WHERE draft_pk IN (
        SELECT draft_pk FROM relay_draft
         WHERE state IN ('PENDING', 'CONFLICT_REPORTED') AND expires_at <= clock_timestamp()
         ORDER BY expires_at LIMIT $1 FOR UPDATE SKIP LOCKED)`,
    [batchSize],
  );
  const acks = await db.query(
    `DELETE FROM relay_ack WHERE (draft_pk, kind) IN (
       SELECT draft_pk, kind FROM relay_ack WHERE expires_at <= clock_timestamp()
        ORDER BY expires_at LIMIT $1 FOR UPDATE SKIP LOCKED)`,
    [batchSize],
  );
  // Replay tombstones and token digests may go only once every credential of the pairing is dead and no ack is retained.
  const pairings = await db.query(
    `DELETE FROM relay_pairing WHERE pairing_id IN (
       SELECT p.pairing_id FROM relay_pairing p
        WHERE p.credential_expires_at <= clock_timestamp()
          AND NOT EXISTS (
            SELECT 1 FROM relay_ack a JOIN relay_draft d ON d.draft_pk = a.draft_pk WHERE d.pairing_id = p.pairing_id)
        ORDER BY p.credential_expires_at LIMIT $1 FOR UPDATE OF p SKIP LOCKED)`,
    [batchSize],
  );
  return { expiredDrafts: expired.rowCount ?? 0, deletedAcks: acks.rowCount ?? 0, deletedPairings: pairings.rowCount ?? 0 };
}
