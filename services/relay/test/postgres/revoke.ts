import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { acceptedAck, ack, api, jpeg, makeDraft, newPairing, pair, PHOTO_CONSENT, submit } from '../support/client.ts';
import { sha256Hex } from '../../src/domain/hash.ts';
import { scenario } from '../support/recorder.ts';
import { env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';

async function submitWithPhoto(p: Awaited<ReturnType<typeof pair>>) {
  const bytes = jpeg();
  const draft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${sha256Hex(bytes)}` } });
  return { draft, sent: await submit(env.base, p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: bytes.toString('base64') }) };
}

export function revokeScenarios(): void {
  scenario('revoke-blocks-and-purges', { ce: ['CE-18'], grade, expected: 'after vehicle revoke: both credentials typed PAIRING_REVOKED, active payload and photo gone, durable ack kept, resend creates nothing' }, async () => {
    const p = await pair(env.base);
    const accepted = makeDraft(p.pairingId);
    const acceptedSent = await submit(env.base, p, accepted);
    await ack(env.base, p, accepted.draftId, 1, acceptedAck(acceptedSent.body.payloadHash, accepted.payload.id));
    const { draft: pending } = await submitWithPhoto(p);
    const photosBefore = (await query(`SELECT count(*)::int AS n FROM relay_draft_photo ph JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    const revoked = await api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: p.vehicleToken });
    const resend = await submit(env.base, p, makeDraft(p.pairingId));
    const resendSame = await submit(env.base, p, pending);
    const vehicleList = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    const phoneStatus = await api(env.base, 'GET', `/phone/v1/drafts/${accepted.draftId}/1`, { token: p.phoneToken });
    const lateAccept = await ack(env.base, p, pending.draftId, 1, acceptedAck('a'.repeat(64), pending.payload.id));
    const claimAfter = await api(env.base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: p.pairingToken } });
    const rows = (await query(`SELECT state, payload IS NULL AS purged FROM relay_draft WHERE pairing_id = $1 ORDER BY draft_pk`, [p.pairingId])).rows;
    const photos = (await query(`SELECT count(*)::int AS n FROM relay_draft_photo ph JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    const acks = (await query(`SELECT count(*)::int AS n FROM relay_ack a JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    const codes = [resend, resendSame, vehicleList, phoneStatus, lateAccept, claimAfter].map((r) => r.body.error.code);
    assert.equal(revoked.status, 200);
    assert.equal(photosBefore, 1);
    assert.deepEqual(codes, Array(6).fill('PAIRING_REVOKED'));
    assert.deepEqual(rows, [{ state: 'ACCEPTED', purged: true }, { state: 'REVOKED', purged: true }]);
    assert.deepEqual([photos, acks], [0, 1]);
    return { codes, rows, photosBefore, photosAfter: photos, durableAcks: acks };
  });

  scenario('phone-revoke', { ce: ['CE-18'], grade, expected: 'the phone can also revoke; the vehicle token then stops working, and an unclaimed QR is refused after revoke' }, async () => {
    const p = await pair(env.base);
    const revoked = await api(env.base, 'POST', '/phone/v1/pairing/revoke', { token: p.phoneToken });
    const vehicle = await api(env.base, 'GET', '/vehicle/v1/pairing', { token: p.vehicleToken });
    const unclaimed = await newPairing(env.base);
    await api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: unclaimed.vehicleToken });
    const claim = await api(env.base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: unclaimed.pairingToken } });
    assert.deepEqual([revoked.status, vehicle.body.error.code, claim.body.error.code], [200, 'PAIRING_REVOKED', 'PAIRING_REVOKED']);
    return { vehicleAfterPhoneRevoke: vehicle.body.error.code, unclaimedQrAfterRevoke: claim.body.error.code };
  });

  scenario('revoke-submit-race', { ce: ['CE-18'], grade, expected: '20 parallel submits racing a revoke: every outcome is 201 or PAIRING_REVOKED; no active draft or payload remains; later resend gains nothing' }, async () => {
    const p = await pair(env.base);
    const drafts = Array.from({ length: 20 }, () => makeDraft(p.pairingId));
    const outcomes = drafts.map((draft) => submit(env.base, p, draft));
    const revoke = api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: p.vehicleToken });
    const results = await Promise.all(outcomes);
    assert.equal((await revoke).status, 200);
    const statuses = results.map((r) => (r.status === 201 ? 201 : r.body.error.code));
    const counted = (await query(`SELECT count(*) FILTER (WHERE state IN ('PENDING','CONFLICT_REPORTED'))::int AS active, count(*) FILTER (WHERE payload IS NOT NULL)::int AS payloads, count(*)::int AS total FROM relay_draft WHERE pairing_id = $1`, [p.pairingId])).rows[0];
    const created = statuses.filter((s) => s === 201).length;
    assert.ok(statuses.every((s) => s === 201 || s === 'PAIRING_REVOKED'));
    assert.deepEqual([counted.active, counted.payloads, counted.total], [0, 0, created]);
    const resend = await submit(env.base, p, makeDraft(p.pairingId));
    assert.equal(resend.body.error.code, 'PAIRING_REVOKED');
    return { created, refused: statuses.length - created, activeAfter: counted.active, payloadsAfter: counted.payloads };
  });

  scenario('revoke-vs-ack-race-and-revoke-after-vehicle-commit', { ce: ['CE-18', 'CE-19'], grade, expected: 'revoke racing acks serializes: a committed ack is durable and any ack after revoke is typed PAIRING_REVOKED; the tombstone stays unchanged' }, async () => {
    const p = await pair(env.base);
    const racing = await Promise.all(Array.from({ length: 6 }, async () => {
      const draft = makeDraft(p.pairingId);
      const sent = await submit(env.base, p, draft);
      return { draft, hash: sent.body.payloadHash };
    }));
    const results = racing.map(({ draft, hash }) => ack(env.base, p, draft.draftId, 1, acceptedAck(hash, randomUUID())));
    const revoke = api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: p.vehicleToken });
    const acks = await Promise.all(results);
    assert.equal((await revoke).status, 200);
    const rows = (await query(`SELECT d.state, d.payload IS NULL AS purged, a.result FROM relay_draft d LEFT JOIN LATERAL (SELECT body->>'result' AS result FROM relay_ack x WHERE x.draft_pk = d.draft_pk) a ON true WHERE d.pairing_id = $1`, [p.pairingId])).rows;
    const applied = acks.filter((r) => r.status === 200).length;
    const refused = acks.filter((r) => r.status === 403 && r.body.error.code === 'PAIRING_REVOKED').length;
    assert.equal(applied + refused, 6);
    assert.equal(rows.filter((r) => r.state === 'ACCEPTED' && r.result === 'VEHICLE_ACCEPTED').length, applied);
    assert.equal(rows.filter((r) => r.state === 'REVOKED' && r.result === null).length, refused);
    assert.ok(rows.every((r) => r.purged));

    // Vehicle committed in Room, then its ack arrives after a revoke: typed refusal, not a retry loop trigger.
    const q = await pair(env.base);
    const draft = makeDraft(q.pairingId);
    const sent = await submit(env.base, q, draft);
    await api(env.base, 'POST', '/phone/v1/pairing/revoke', { token: q.phoneToken });
    const lateAck = await ack(env.base, q, draft.draftId, 1, acceptedAck(sent.body.payloadHash, draft.payload.id));
    const tombstone = (await query('SELECT state, payload_sha256 FROM relay_draft WHERE pairing_id = $1', [q.pairingId])).rows[0];
    assert.equal(lateAck.body.error.code, 'PAIRING_REVOKED');
    assert.deepEqual([tombstone.state, tombstone.payload_sha256], ['REVOKED', sent.body.payloadHash]);
    return { applied, refused, ackAfterVehicleCommit: lateAck.body.error.code, tombstone: tombstone.state };
  });
}
