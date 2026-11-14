import assert from 'node:assert/strict';
import pg from 'pg';
import { acceptedAck, ack, api, jpeg, makeDraft, newPairing, pair, PHOTO_CONSENT, submit } from '../support/client.ts';
import { runCleanup } from '../../src/db/cleanup.ts';
import { sha256Hex } from '../../src/domain/hash.ts';
import { sleep } from '../support/pg-harness.ts';
import { scenario } from '../support/recorder.ts';
import { migrate, startRelay, type RelayProcess } from '../support/relay-process.ts';
import { DB, env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';
const SHORT = 'test/fixtures/config-short-ttl.json';

const untilPast = async (iso: string, slackMs = 150) => {
  const wait = new Date(iso).getTime() - Date.now() + slackMs;
  if (wait > 0) await sleep(wait);
};

export function ttlScenarios(): void {
  let relay: RelayProcess;
  const base = () => relay.base;
  const sweep = async () => runCleanup(env.db, 1000);

  scenario('short-ttl-relay-start', { grade, expected: 'test-profile relay with real short TTLs (pairing 2s, draft 3s, ack 2s, credential 8s) and no background cleanup starts' }, async () => {
    assert.equal(migrate(env.cluster, DB, SHORT).status, 0);
    relay = await startRelay({ databaseUrl: env.cluster.runtimeUrl(DB), configFile: SHORT });
    env.extra.push(relay);
    return { port: relay.port };
  });

  scenario('pairing-expiry-boundary', { ce: ['CE-18'], grade, expected: 'QR claim succeeds immediately, is typed PAIRING_EXPIRED right after expiry even though no cleanup ran' }, async () => {
    const fresh = await newPairing(base());
    const okClaim = await api(base(), 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: fresh.pairingToken } });
    const late = await newPairing(base());
    await untilPast(late.created.claimExpiresAt);
    const lateClaim = await api(base(), 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: late.pairingToken } });
    assert.equal(okClaim.status, 200);
    assert.deepEqual([lateClaim.status, lateClaim.body.error.code], [410, 'PAIRING_EXPIRED']);
    const stale = await api(base(), 'GET', '/vehicle/v1/pairing', { token: late.vehicleToken });
    assert.equal(stale.body.error.code, 'CREDENTIAL_EXPIRED');
    return { beforeExpiry: okClaim.status, afterExpiry: lateClaim.body.error.code, unclaimedVehicleToken: stale.body.error.code };
  });

  scenario('draft-expiry-reads-without-cleanup', { ce: ['CE-18', 'CE-20'], grade, expected: 'after draft TTL and before any cleanup: vehicle list hides it, photo 404, ack typed DRAFT_EXPIRED, phone sees EXPIRED; cleanup then purges payload and photo and a second run is a no-op for it' }, async () => {
    const p = await pair(base());
    const bytes = jpeg();
    const draft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${sha256Hex(bytes)}` } });
    const sent = await submit(base(), p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: bytes.toString('base64') });
    assert.equal(sent.status, 201);
    const visible = await api(base(), 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    assert.equal(visible.body.drafts.length, 1);
    await untilPast(sent.body.expiresAt);
    const list = await api(base(), 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    const photo = await api(base(), 'GET', `/vehicle/v1/drafts/${draft.draftId}/1/photo`, { token: p.vehicleToken });
    const lateAck = await ack(base(), p, draft.draftId, 1, acceptedAck(sent.body.payloadHash, draft.payload.id));
    const status = await api(base(), 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    const replay = await submit(base(), p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: bytes.toString('base64') });
    const stillStored = (await query(`SELECT state, payload IS NOT NULL AS has_payload FROM relay_draft WHERE pairing_id = $1`, [p.pairingId])).rows[0];
    assert.deepEqual([list.body.drafts.length, photo.status, lateAck.body.error.code, status.body.state, replay.status, replay.body.state], [0, 404, 'DRAFT_EXPIRED', 'EXPIRED', 200, 'EXPIRED']);
    assert.deepEqual(stillStored, { state: 'PENDING', has_payload: true }); // proves reads did not rely on cleanup
    const first = await sweep();
    const row = (await query(`SELECT state, payload IS NULL AS purged FROM relay_draft WHERE pairing_id = $1`, [p.pairingId])).rows[0];
    const photos = (await query(`SELECT count(*)::int AS n FROM relay_draft_photo ph JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    assert.deepEqual([row.state, row.purged, photos], ['EXPIRED', true, 0]);
    const second = await sweep();
    return { lateAck: lateAck.body.error.code, phoneState: status.body.state, firstSweep: first, secondSweepExpired: second.expiredDrafts, photosAfter: photos };
  });

  scenario('ack-ttl-and-tombstone-replay-boundary', { ce: ['CE-18'], grade, expected: 'ack row disappears after ack TTL, yet while the credential lives the tombstone keeps the terminal state so resend cannot re-apply' }, async () => {
    const p = await pair(base());
    const draft = makeDraft(p.pairingId);
    const sent = await submit(base(), p, draft);
    const acked = await ack(base(), p, draft.draftId, 1, acceptedAck(sent.body.payloadHash, draft.payload.id));
    assert.equal(acked.status, 200);
    const during = await api(base(), 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    assert.equal(during.body.terminalAck.acceptedRevision, 7);
    await sleep(2200);
    const after = await api(base(), 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    await sweep();
    const rowsAfterSweep = (await query(`SELECT count(*)::int AS n FROM relay_ack a JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    const resend = await submit(base(), p, draft);
    const rows = (await query(`SELECT count(*)::int AS n, min(state) AS state FROM relay_draft WHERE pairing_id = $1`, [p.pairingId])).rows[0];
    assert.deepEqual([after.body.state, after.body.terminalAck], ['ACCEPTED', null]);
    assert.equal(rowsAfterSweep, 0);
    assert.deepEqual([resend.status, resend.body.state, rows.n, rows.state], [200, 'ACCEPTED', 1, 'ACCEPTED']);
    return { ackVisibleAfterTtl: after.body.terminalAck, ackRowsAfterSweep: rowsAfterSweep, resendState: resend.body.state, draftRows: rows.n };
  });

  scenario('credential-expiry-then-cleanup-no-new-accept', { ce: ['CE-18'], grade, expected: 'just before credential expiry a submit works and its draft expiry is capped at credential expiry; after expiry resend is CREDENTIAL_EXPIRED; after cleanup the same token is a uniform 401; zero new rows either way' }, async () => {
    const p = await pair(base());
    const n1 = makeDraft(p.pairingId);
    const sent1 = await submit(base(), p, n1);
    assert.equal(sent1.status, 201);
    const credentialExpiry = new Date(p.claimed.credentialExpiresAt).getTime();
    await sleep(Math.max(0, credentialExpiry - Date.now() - 1500));
    const n2 = makeDraft(p.pairingId, { draftId: n1.draftId, clientRevision: 2 });
    const justBefore = await submit(base(), p, n2);
    assert.equal(justBefore.status, 201);
    const capped = new Date(justBefore.body.expiresAt).getTime() <= credentialExpiry;
    assert.ok(capped, 'draft expiry must not outlive the credential');
    await untilPast(p.claimed.credentialExpiresAt);
    const n3 = makeDraft(p.pairingId, { draftId: n1.draftId, clientRevision: 3 });
    const justAfter = await submit(base(), p, n3);
    const replayAfter = await submit(base(), p, n2);
    const countBeforeCleanup = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    assert.deepEqual([justAfter.status, justAfter.body.error.code, replayAfter.body.error.code, countBeforeCleanup], [401, 'CREDENTIAL_EXPIRED', 'CREDENTIAL_EXPIRED', 2]);
    await sweep();
    const gone = (await query('SELECT count(*)::int AS n FROM relay_pairing WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    const creds = (await query('SELECT count(*)::int AS n FROM relay_credential WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    const afterCleanup = await submit(base(), p, n3);
    const drafts = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    assert.deepEqual([gone, creds, afterCleanup.status, afterCleanup.body.error.code, drafts], [0, 0, 401, 'UNAUTHENTICATED', 0]);
    return { justBefore: justBefore.status, draftExpiryCappedToCredential: capped, justAfter: justAfter.body.error.code, afterCleanup: afterCleanup.body.error.code, rowsLeft: drafts };
  });

  scenario('cleanup-skip-locked-and-idempotent', { ce: ['CE-20'], grade, expected: 'a row locked by another transaction is skipped without blocking; two parallel sweeps do not double-process; the lock released row is handled next time' }, async () => {
    const p = await pair(base());
    const draft = makeDraft(p.pairingId);
    const sent = await submit(base(), p, draft);
    await untilPast(sent.body.expiresAt);
    const locker = new pg.Client({ connectionString: env.cluster.runtimeUrl(DB) });
    await locker.connect();
    await locker.query('BEGIN');
    await locker.query('SELECT 1 FROM relay_draft WHERE pairing_id = $1 FOR UPDATE', [p.pairingId]);
    const started = Date.now();
    await sweep();
    const elapsed = Date.now() - started;
    const whileLocked = (await query('SELECT state FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].state;
    await locker.query('ROLLBACK');
    await locker.end();
    const parallel = await Promise.all([sweep(), sweep(), sweep()]);
    const finalState = (await query('SELECT state FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].state;
    assert.equal(whileLocked, 'PENDING');
    assert.ok(elapsed < 3000);
    assert.equal(finalState, 'EXPIRED');
    return { sweepWhileLockedMs: elapsed, whileLocked, finalState, parallelExpired: parallel.map((r) => r.expiredDrafts) };
  });

  scenario('expiry-during-row-lock-wait', { ce: ['CE-18'], grade, expected: 'when the pairing row lock is held past a TTL, requests queued before expiry are judged by the time after they get the lock: QR claim 410 with no credential issued, submit and ack CREDENTIAL_EXPIRED or DRAFT_EXPIRED with no state change' }, async () => {
    const holdLockPast = async (pairingId: string, expiresAtIso: string, request: () => Promise<any>) => {
      const locker = new pg.Client({ connectionString: env.cluster.runtimeUrl(DB) });
      await locker.connect();
      await locker.query('BEGIN');
      await locker.query('SELECT 1 FROM relay_pairing WHERE pairing_id = $1 FOR UPDATE', [pairingId]);
      const pending = request();
      await sleep(Math.max(100, new Date(expiresAtIso).getTime() - Date.now() + 300));
      const expired = (await locker.query('SELECT clock_timestamp() > $1::timestamptz AS expired', [expiresAtIso])).rows[0].expired;
      await locker.query('COMMIT');
      await locker.end();
      assert.equal(expired, true);
      return pending;
    };

    const qr = await newPairing(base());
    const claim = await holdLockPast(qr.pairingId, qr.created.claimExpiresAt, () => api(base(), 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: qr.pairingToken } }));
    const phoneCredentials = (await query(`SELECT count(*)::int AS n FROM relay_credential WHERE pairing_id = $1 AND role = 'PHONE'`, [qr.pairingId])).rows[0].n;
    assert.deepEqual([claim.status, claim.body.error?.code, 'phoneToken' in claim.body, phoneCredentials], [410, 'PAIRING_EXPIRED', false, 0]);

    const late = await pair(base());
    const lapsing = makeDraft(late.pairingId);
    const sent = await submit(base(), late, lapsing);
    const lateAck = await holdLockPast(late.pairingId, sent.body.expiresAt, () => ack(base(), late, lapsing.draftId, 1, acceptedAck(sent.body.payloadHash, lapsing.payload.id)));
    const ackRows = (await query(`SELECT count(*)::int AS n FROM relay_ack a JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [late.pairingId])).rows[0].n;
    assert.deepEqual([lateAck.status, lateAck.body.error?.code, ackRows], [410, 'DRAFT_EXPIRED', 0]);

    const lateDraft = makeDraft(late.pairingId);
    const lateSubmit = await holdLockPast(late.pairingId, late.claimed.credentialExpiresAt, () => submit(base(), late, lateDraft));
    const rows = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1 AND draft_id = $2', [late.pairingId, lateDraft.draftId])).rows[0].n;
    assert.deepEqual([lateSubmit.status, lateSubmit.body.error?.code, rows], [401, 'CREDENTIAL_EXPIRED', 0]);
    return { claim: claim.body.error.code, phoneCredentials, ack: lateAck.body.error.code, ackRows, submit: lateSubmit.body.error.code, newDraftRows: rows };
  });

  scenario('short-ttl-relay-stop', { grade, expected: 'short-TTL relay stops cleanly' }, async () => {
    const exit = await relay.stop();
    assert.equal(exit.code, 0);
    return exit;
  });
}
