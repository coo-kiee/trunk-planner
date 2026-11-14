// Draft mailbox: idempotent submit, vehicle fetch, typed ack CAS, phone status. All inside the pairing row lock.
import type { RelayConfig } from '../config.ts';
import type { AckKind, DraftState, parseAck } from '../domain/ack.ts';
import { RelayError } from '../domain/errors.ts';
import type { ValidSubmission } from '../domain/submission.ts';
import { inTransaction, lockPairing, type Db, type PairingContext, type Tx } from './pool.ts';

interface DraftRow {
  draft_pk: number;
  draft_id: string;
  client_revision: number;
  payload_sha256: string;
  state: DraftState;
  received_at: Date;
  expires_at: Date;
  is_lapsed: boolean;
}

// An active draft past expires_at reads as EXPIRED before cleanup runs: reads never depend on the cleanup job.
const DRAFT_COLUMNS = `draft_pk, draft_id, client_revision, payload_sha256, state, received_at, expires_at,
  (state IN ('PENDING', 'CONFLICT_REPORTED') AND expires_at <= clock_timestamp()) AS is_lapsed`;

const effectiveState = (row: DraftRow): DraftState => (row.is_lapsed ? 'EXPIRED' : row.state);

function receipt(row: DraftRow, pairingId: string, duplicate: boolean) {
  return {
    pairingId,
    draftId: row.draft_id,
    clientRevision: row.client_revision,
    payloadHash: row.payload_sha256,
    state: effectiveState(row),
    receivedAt: row.received_at.toISOString(),
    expiresAt: row.expires_at.toISOString(),
    duplicate,
  };
}

async function assertPairingCapacity(tx: Tx, ctx: PairingContext, incoming: number, config: RelayConfig): Promise<void> {
  const usage = await tx.query<{ rows_total: number; active_rows: number; active_bytes: number }>(
    `SELECT count(*) AS rows_total,
            count(*) FILTER (WHERE state IN ('PENDING', 'CONFLICT_REPORTED')) AS active_rows,
            COALESCE(sum(payload_bytes) FILTER (WHERE state IN ('PENDING', 'CONFLICT_REPORTED')), 0)::bigint AS active_bytes
       FROM relay_draft WHERE pairing_id = $1`,
    [ctx.pairingId],
  );
  const { rows_total, active_rows, active_bytes } = usage.rows[0]!;
  const { maxDraftRowsPerPairing, maxActiveDraftsPerPairing, maxActiveBytesPerPairing } = config.limits;
  if (rows_total >= maxDraftRowsPerPairing || active_rows >= maxActiveDraftsPerPairing) {
    throw new RelayError(429, 'PAIRING_DRAFT_LIMIT', {}, 60);
  }
  if (Number(active_bytes) + incoming > maxActiveBytesPerPairing) throw new RelayError(413, 'PAIRING_BYTES_LIMIT');
}

export async function submitDraft(db: Db, token: string, submission: ValidSubmission, config: RelayConfig) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, ['PHONE'], 'UPDATE');
    const { draft, consent, photo, payloadSha256, payloadBytes } = submission;
    if (draft.pairingId !== ctx.pairingId) throw new RelayError(403, 'PAIRING_MISMATCH');

    const existing = await tx.query<DraftRow>(
      `SELECT ${DRAFT_COLUMNS} FROM relay_draft WHERE pairing_id = $1 AND draft_id = $2 AND client_revision = $3`,
      [ctx.pairingId, draft.draftId, draft.clientRevision],
    );
    const same = existing.rows[0];
    if (same) {
      if (same.payload_sha256 !== payloadSha256) throw new RelayError(409, 'DRAFT_CONTENT_CONFLICT');
      return receipt(same, ctx.pairingId, true);
    }

    const newest = await tx.query<{ max: number | null }>(
      'SELECT max(client_revision) AS max FROM relay_draft WHERE pairing_id = $1 AND draft_id = $2',
      [ctx.pairingId, draft.draftId],
    );
    const newestRevision = newest.rows[0]?.max ?? null;
    if (newestRevision !== null && newestRevision > draft.clientRevision) {
      throw new RelayError(409, 'STALE_CLIENT_REVISION', { newestClientRevision: newestRevision });
    }

    await assertPairingCapacity(tx, ctx, payloadBytes, config);
    await tx.query(
      `UPDATE relay_draft SET state = 'SUPERSEDED', payload = NULL, terminal_at = clock_timestamp()
        WHERE pairing_id = $1 AND draft_id = $2 AND state IN ('PENDING', 'CONFLICT_REPORTED')`,
      [ctx.pairingId, draft.draftId],
    );
    const inserted = await tx.query<DraftRow>(
      `INSERT INTO relay_draft (pairing_id, draft_id, client_revision, payload_sha256, state, payload, payload_bytes,
                                consent_version, dimensions_consent, photo_consent, received_at, expires_at)
       VALUES ($1, $2, $3, $4, 'PENDING', $5, $6, $7, $8, $9, clock_timestamp(),
               LEAST(clock_timestamp() + make_interval(secs => $10::int), $11::timestamptz))
       RETURNING ${DRAFT_COLUMNS}`,
      [ctx.pairingId, draft.draftId, draft.clientRevision, payloadSha256, JSON.stringify(draft), payloadBytes,
        consent.consentVersion, consent.dimensionsConsent, consent.photoConsent, ctx.ttl.draft, ctx.credentialExpiresAt],
    );
    const row = inserted.rows[0]!;
    if (photo) {
      await tx.query(
        'INSERT INTO relay_draft_photo (draft_pk, mime, bytes, sha256, width, height) VALUES ($1, $2, $3, $4, $5, $6)',
        [row.draft_pk, photo.mime, photo.bytes, photo.sha256, photo.width, photo.height],
      );
    }
    return receipt(row, ctx.pairingId, false);
  });
}

export async function listDraftsForVehicle(db: Db, token: string, afterSeq: number, limit: number) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, ['VEHICLE'], 'SHARE');
    const rows = await tx.query<DraftRow & {
      payload: unknown; consent_version: string; photo_consent: boolean;
      mime: string | null; sha256: string | null; byte_length: number | null; width: number | null; height: number | null;
      conflict_body: unknown;
    }>(
      `SELECT ${DRAFT_COLUMNS.replaceAll('draft_pk,', 'd.draft_pk,')}, d.payload, d.consent_version, d.photo_consent,
              ph.mime, ph.sha256, octet_length(ph.bytes) AS byte_length, ph.width, ph.height,
              (SELECT a.body FROM relay_ack a WHERE a.draft_pk = d.draft_pk AND a.kind = 'CONFLICT') AS conflict_body
         FROM relay_draft d LEFT JOIN relay_draft_photo ph ON ph.draft_pk = d.draft_pk
        WHERE d.pairing_id = $1 AND d.draft_pk > $2
          AND d.state IN ('PENDING', 'CONFLICT_REPORTED') AND d.expires_at > clock_timestamp()
        ORDER BY d.draft_pk LIMIT $3`,
      [ctx.pairingId, afterSeq, limit],
    );
    return {
      pairingId: ctx.pairingId,
      drafts: rows.rows.map((row) => ({
        seq: row.draft_pk,
        draftId: row.draft_id,
        clientRevision: row.client_revision,
        state: row.state,
        payloadHash: row.payload_sha256,
        draft: row.payload,
        consent: { consentVersion: row.consent_version, dimensionsConsent: true, photoConsent: row.photo_consent },
        photo: row.mime ? { mime: row.mime, sha256: row.sha256, byteLength: row.byte_length, width: row.width, height: row.height } : null,
        conflict: row.conflict_body ?? null,
        receivedAt: row.received_at.toISOString(),
        expiresAt: row.expires_at.toISOString(),
      })),
    };
  });
}

export async function readPhotoForVehicle(db: Db, token: string, draftId: string, clientRevision: number) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, ['VEHICLE'], 'SHARE');
    const found = await tx.query<{ mime: string; bytes: Buffer; sha256: string }>(
      `SELECT ph.mime, ph.bytes, ph.sha256
         FROM relay_draft_photo ph JOIN relay_draft d ON d.draft_pk = ph.draft_pk
        WHERE d.pairing_id = $1 AND d.draft_id = $2 AND d.client_revision = $3
          AND d.state IN ('PENDING', 'CONFLICT_REPORTED') AND d.expires_at > clock_timestamp()`,
      [ctx.pairingId, draftId, clientRevision],
    );
    const photo = found.rows[0];
    if (!photo) throw new RelayError(404, 'PHOTO_NOT_FOUND');
    return photo;
  });
}

async function readAcks(tx: Tx, draftPk: number) {
  const acks = await tx.query<{ kind: AckKind; body: Record<string, unknown>; ack_sha256: string; received_at: Date }>(
    `SELECT kind, body, ack_sha256, received_at FROM relay_ack WHERE draft_pk = $1 AND expires_at > clock_timestamp()`,
    [draftPk],
  );
  return acks.rows;
}

type ParsedAck = ReturnType<typeof parseAck>;

/** Vehicle reports its decision. CAS on expected state and payload hash; identical replays get the original receipt. */
export async function recordAck(db: Db, token: string, draftId: string, clientRevision: number, parsed: ParsedAck) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, ['VEHICLE'], 'UPDATE');
    const found = await tx.query<DraftRow>(
      `SELECT ${DRAFT_COLUMNS} FROM relay_draft WHERE pairing_id = $1 AND draft_id = $2 AND client_revision = $3 FOR UPDATE`,
      [ctx.pairingId, draftId, clientRevision],
    );
    const row = found.rows[0];
    if (!row) throw new RelayError(404, 'DRAFT_NOT_FOUND');
    if (parsed.ack.payloadHash !== row.payload_sha256) throw new RelayError(409, 'PAYLOAD_HASH_MISMATCH');

    const acks = await readAcks(tx, row.draft_pk);
    const prior = acks.find((ack) => ack.kind === parsed.kind);
    if (prior) {
      if (prior.ack_sha256 !== parsed.sha256) throw new RelayError(409, 'ACK_MISMATCH', { currentState: effectiveState(row) });
      return { draftId, clientRevision, state: effectiveState(row), ack: prior.body, receivedAt: prior.received_at.toISOString(), replayed: true };
    }
    if (row.is_lapsed) throw new RelayError(410, 'DRAFT_EXPIRED', { currentState: 'EXPIRED' });
    if (row.state !== parsed.ack.expectedState) {
      throw new RelayError(409, 'STATE_CONFLICT', { currentState: row.state });
    }

    const terminal = parsed.nextState !== 'CONFLICT_REPORTED';
    const moved = await tx.query(
      `UPDATE relay_draft
          SET state = $2, payload = CASE WHEN $3::boolean THEN NULL ELSE payload END,
              terminal_at = CASE WHEN $3::boolean THEN clock_timestamp() ELSE NULL END
        WHERE draft_pk = $1 AND state = $4 AND expires_at > clock_timestamp()`,
      [row.draft_pk, parsed.nextState, terminal, parsed.ack.expectedState],
    );
    // The draft row lock is held and its state was just compared, so only expiry during a lock wait can miss here.
    if (moved.rowCount !== 1) throw new RelayError(410, 'DRAFT_EXPIRED', { currentState: 'EXPIRED' });
    const stored = await tx.query<{ received_at: Date }>(
      `INSERT INTO relay_ack (draft_pk, kind, ack_sha256, body, received_at, expires_at)
       VALUES ($1, $2, $3, $4, clock_timestamp(), clock_timestamp() + make_interval(secs => $5::int))
       RETURNING received_at`,
      [row.draft_pk, parsed.kind, parsed.sha256, JSON.stringify(parsed.ack), ctx.ttl.ack],
    );
    return { draftId, clientRevision, state: parsed.nextState, ack: parsed.ack, receivedAt: stored.rows[0]!.received_at.toISOString(), replayed: false };
  });
}

/** Phone view. `received` from the relay never means applied on the vehicle; only VEHICLE_ACCEPTED carries that. */
export async function readDraftStatus(db: Db, token: string, draftId: string, clientRevision: number) {
  return inTransaction(db, async (tx) => {
    const ctx = await lockPairing(tx, token, ['PHONE'], 'SHARE');
    const found = await tx.query<DraftRow>(
      `SELECT ${DRAFT_COLUMNS} FROM relay_draft WHERE pairing_id = $1 AND draft_id = $2 AND client_revision = $3`,
      [ctx.pairingId, draftId, clientRevision],
    );
    const row = found.rows[0];
    if (!row) throw new RelayError(404, 'DRAFT_NOT_FOUND');
    const acks = await readAcks(tx, row.draft_pk);
    const body = (kind: AckKind) => acks.find((ack) => ack.kind === kind)?.body ?? null;
    return { ...receipt(row, ctx.pairingId, false), conflict: body('CONFLICT'), terminalAck: body('TERMINAL') };
  });
}
