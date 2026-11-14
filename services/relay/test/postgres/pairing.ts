import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { api, makeDraft, newPairing, pair, submit } from '../support/client.ts';
import { scenario } from '../support/recorder.ts';
import { startRelay } from '../support/relay-process.ts';
import { DB, env, query } from '../support/suite-env.ts';
import { migrate } from '../support/relay-process.ts';

const grade = 'real_postgres_local_integration';

export function pairingScenarios(): void {
  scenario('prod-config-ttl-stamps', { ce: ['CE-18'], grade, expected: 'production relay-config-v1 + contracts configuration-v1: pairing 300s, credential 86400s, draft ≤86400s, ack 86400s stamped by DB clock' }, async () => {
    assert.equal(migrate(env.cluster, DB).status, 0);
    const relay = await startRelay({ databaseUrl: env.cluster.runtimeUrl(DB) });
    env.extra.push(relay);
    const p = await pair(relay.base);
    const draftBody = makeDraft(p.pairingId);
    const sent = await submit(relay.base, p, draftBody);
    assert.equal(sent.status, 201);
    await api(relay.base, 'POST', `/vehicle/v1/drafts/${draftBody.draftId}/1/ack`, {
      token: p.vehicleToken,
      json: { ackVersion: 1, expectedState: 'PENDING', payloadHash: sent.body.payloadHash, result: 'VEHICLE_REJECTED', rejectCode: 'USER_DISCARDED' },
    });
    const row = (await query(
      `SELECT p.configuration_version AS version,
              extract(epoch FROM p.claim_expires_at - p.created_at)::int AS pairing_s,
              extract(epoch FROM p.credential_expires_at - p.claimed_at)::int AS credential_s,
              round(extract(epoch FROM d.expires_at - d.received_at))::int AS draft_s,
              round(extract(epoch FROM a.expires_at - a.received_at))::int AS ack_s
         FROM relay_pairing p JOIN relay_draft d USING (pairing_id) JOIN relay_ack a USING (draft_pk) WHERE p.pairing_id = $1`, [p.pairingId])).rows[0];
    await relay.stop();
    assert.deepEqual([row.version, row.pairing_s, row.credential_s, row.ack_s], [1, 300, 86400, 86400]);
    assert.ok(row.draft_s <= 86400 && row.draft_s >= 86399);
    return row;
  });

  scenario('qr-url-uses-fragment-only', { grade, expected: 'QR URL carries the token only in the fragment; phone and vehicle base URLs are independent' }, async () => {
    const p = await newPairing(env.base);
    const url = new URL(p.created.qrUrl);
    assert.equal(url.search, '');
    assert.ok(url.hash.startsWith('#pairing=qp_'));
    assert.equal(url.origin, 'http://127.0.0.1:43001');
    assert.equal(p.created.vehicleRelayBaseUrl, 'http://10.0.2.2:43002/');
    return { search: url.search, fragmentKey: 'pairing', qrOrigin: url.origin, vehicleRelayBaseUrl: p.created.vehicleRelayBaseUrl };
  });

  scenario('tokens-stored-only-as-digest', { ce: ['CE-18'], grade, expected: 'relay_credential holds sha256(token) as 32 bytes; plaintext appears in no column' }, async () => {
    const p = await pair(env.base);
    const digests = (await query('SELECT role, token_digest FROM relay_credential WHERE pairing_id = $1 ORDER BY role', [p.pairingId])).rows;
    const expected = new Map([['PAIRING', p.pairingToken], ['VEHICLE', p.vehicleToken], ['PHONE', p.phoneToken]]);
    for (const row of digests) {
      assert.equal(row.token_digest.length, 32);
      assert.ok(row.token_digest.equals(createHash('sha256').update(expected.get(row.role)!).digest()));
    }
    assert.equal(digests.length, 3);
    return { rows: digests.length, digestBytes: 32 };
  });

  scenario('concurrent-claim-exactly-one', { ce: ['CE-18'], grade, expected: '12 parallel claims of one QR: exactly one 200, others typed 409 PAIRING_TOKEN_CONSUMED, one PHONE credential' }, async () => {
    const p = await newPairing(env.base);
    const results = await Promise.all(Array.from({ length: 12 }, () => api(env.base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: p.pairingToken } })));
    const ok = results.filter((r) => r.status === 200);
    const consumed = results.filter((r) => r.status === 409 && r.body.error.code === 'PAIRING_TOKEN_CONSUMED');
    const phones = (await query(`SELECT count(*)::int AS n FROM relay_credential WHERE pairing_id = $1 AND role = 'PHONE'`, [p.pairingId])).rows[0].n;
    assert.deepEqual([ok.length, consumed.length, phones], [1, 11, 1]);
    return { success: ok.length, consumed: consumed.length, phoneCredentials: phones };
  });

  scenario('claim-response-lost-requires-new-qr', { ce: ['CE-18'], grade, expected: 'retry after a lost claim response never re-issues a secret; new QR gives a new pairingId and old-pairing drafts are refused until re-wrapped' }, async () => {
    const first = await pair(env.base); // the phoneToken in this response counts as lost
    const retry = await api(env.base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: first.pairingToken } });
    const second = await pair(env.base);
    const unwrapped = await submit(env.base, second, makeDraft(first.pairingId));
    const rewrapped = await submit(env.base, second, makeDraft(second.pairingId));
    assert.equal(retry.status, 409);
    assert.equal(retry.body.error.code, 'PAIRING_TOKEN_CONSUMED');
    assert.equal('phoneToken' in retry.body, false);
    assert.notEqual(first.pairingId, second.pairingId);
    assert.equal(unwrapped.body.error.code, 'PAIRING_MISMATCH');
    assert.equal(rewrapped.status, 201);
    return { retry: retry.body.error.code, oldPairingDraft: unwrapped.body.error.code, rewrappedStatus: rewrapped.status };
  });

  scenario('role-and-cross-pair-authorization', { ce: ['CE-18'], grade, expected: 'each token works only on its namespace and pairing; unknown tokens get one uniform 401' }, async () => {
    const a = await pair(env.base);
    const b = await pair(env.base);
    const draft = makeDraft(a.pairingId);
    const sent = await submit(env.base, a, draft);
    const phoneOnVehicle = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: a.phoneToken });
    const vehicleOnPhone = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: a.vehicleToken });
    const qrAsBearer = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: a.pairingToken });
    const otherList = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: b.vehicleToken });
    const otherRead = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: b.phoneToken });
    const otherAck = await api(env.base, 'POST', `/vehicle/v1/drafts/${draft.draftId}/1/ack`, {
      token: b.vehicleToken,
      json: { ackVersion: 1, expectedState: 'PENDING', payloadHash: sent.body.payloadHash, result: 'VEHICLE_REJECTED', rejectCode: 'USER_DISCARDED' },
    });
    const otherSubmit = await submit(env.base, b, draft);
    const phoneRevokesViaVehicle = await api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: a.phoneToken });
    const vehicleRevokesViaPhone = await api(env.base, 'POST', '/phone/v1/pairing/revoke', { token: a.vehicleToken });
    const unknown = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: `vh_${'A'.repeat(43)}` });
    const unknownPhone = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: `ph_${'B'.repeat(43)}` });
    const malformed = await api(env.base, 'GET', '/vehicle/v1/drafts', { headers: { authorization: 'Bearer nope' } });
    const none = await api(env.base, 'GET', '/vehicle/v1/drafts');
    const statuses = {
      phoneOnVehicle: phoneOnVehicle.body.error.code,
      vehicleOnPhone: vehicleOnPhone.body.error.code,
      qrAsBearer: qrAsBearer.body.error.code,
      crossNamespaceRevoke: [phoneRevokesViaVehicle.body.error.code, vehicleRevokesViaPhone.body.error.code],
      otherListCount: otherList.body.drafts.length,
      otherRead: otherRead.body.error.code,
      otherAck: otherAck.body.error.code,
      otherSubmit: otherSubmit.body.error.code,
      unknown: [unknown.status, unknown.body.error.code],
      unknownPhone: [unknownPhone.status, unknownPhone.body.error.code],
      malformed: [malformed.status, malformed.body.error.code],
      none: [none.status, none.body.error.code],
    };
    assert.deepEqual(statuses, {
      phoneOnVehicle: 'ROLE_FORBIDDEN', vehicleOnPhone: 'ROLE_FORBIDDEN', qrAsBearer: 'ROLE_FORBIDDEN', crossNamespaceRevoke: ['ROLE_FORBIDDEN', 'ROLE_FORBIDDEN'], otherListCount: 0,
      otherRead: 'DRAFT_NOT_FOUND', otherAck: 'DRAFT_NOT_FOUND', otherSubmit: 'PAIRING_MISMATCH',
      unknown: [401, 'UNAUTHENTICATED'], unknownPhone: [401, 'UNAUTHENTICATED'], malformed: [401, 'UNAUTHENTICATED'], none: [401, 'UNAUTHENTICATED'],
    });
    return statuses;
  });
}
