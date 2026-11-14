import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { acceptedAck, ack, api, conflictAck, makeDraft, pair, rejectedAck, submit } from '../support/client.ts';
import { scenario } from '../support/recorder.ts';
import { env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';

export function ackScenarios(): void {
  scenario('conflict-then-accepted-revision-relayed', { ce: ['CE-19'], grade, expected: 'vehicle-reported conflict then acceptedRevision reach the phone verbatim; the relay does not choose or compare the revision' }, async () => {
    const p = await pair(env.base);
    const baseItem = randomUUID();
    const draft = { ...makeDraft(p.pairingId), baseItemId: baseItem, baseItemRevision: 3 };
    const sent = await submit(env.base, p, draft);
    const hash = sent.body.payloadHash;
    const conflict = await ack(env.base, p, draft.draftId, 1, conflictAck(hash, baseItem, 9));
    const phoneDuring = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    const vehicleDuring = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    // Revision 42 has no relation to base 3 or conflict 9: the relay must not interpret it.
    const accepted = await ack(env.base, p, draft.draftId, 1, acceptedAck(hash, baseItem, 42, 'CONFLICT_REPORTED'));
    const phoneAfter = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    const row = (await query('SELECT state, payload IS NULL AS purged FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0];
    assert.equal(conflict.status, 200);
    assert.deepEqual([phoneDuring.body.state, phoneDuring.body.conflict.authoritativeRevision, phoneDuring.body.terminalAck], ['CONFLICT_REPORTED', 9, null]);
    assert.equal(vehicleDuring.body.drafts[0].conflict.authoritativeRevision, 9);
    assert.equal(vehicleDuring.body.drafts[0].draft.draftId, draft.draftId);
    assert.deepEqual([accepted.status, phoneAfter.body.state, phoneAfter.body.terminalAck.acceptedRevision], [200, 'ACCEPTED', 42]);
    assert.deepEqual(row, { state: 'ACCEPTED', purged: true });
    return { duringConflict: phoneDuring.body.state, conflictRevision: 9, finalState: phoneAfter.body.state, acceptedRevision: 42, payloadPurged: row.purged };
  });

  scenario('conflict-user-kept-vehicle-version', { ce: ['CE-19'], grade, expected: 'user keeping the vehicle version is a typed terminal rejection the phone can distinguish; nothing is silently overwritten' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const sent = await submit(env.base, p, draft);
    await ack(env.base, p, draft.draftId, 1, conflictAck(sent.body.payloadHash, randomUUID()));
    const rejected = await ack(env.base, p, draft.draftId, 1, rejectedAck(sent.body.payloadHash, 'USER_KEPT_VEHICLE_VERSION', 'CONFLICT_REPORTED'));
    const phone = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    assert.deepEqual([rejected.status, phone.body.state, phone.body.terminalAck.rejectCode], [200, 'REJECTED', 'USER_KEPT_VEHICLE_VERSION']);
    return { state: phone.body.state, rejectCode: phone.body.terminalAck.rejectCode };
  });

  scenario('ack-idempotent-and-immutable', { ce: ['CE-19', 'CE-18'], grade, expected: 'identical ack replays the first receipt; a different ack is 409 ACK_MISMATCH; terminal states never move; wrong hash and invalid shapes are refused' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const sent = await submit(env.base, p, draft);
    const hash = sent.body.payloadHash;
    const itemId = randomUUID();
    const wrongHash = await ack(env.base, p, draft.draftId, 1, acceptedAck('0'.repeat(64), itemId));
    const invalid = await ack(env.base, p, draft.draftId, 1, { ...acceptedAck(hash, itemId), acceptedRevision: 0 });
    const extra = await ack(env.base, p, draft.draftId, 1, { ...acceptedAck(hash, itemId), scene: 1 });
    const first = await ack(env.base, p, draft.draftId, 1, acceptedAck(hash, itemId, 7));
    const replay = await ack(env.base, p, draft.draftId, 1, acceptedAck(hash, itemId, 7));
    const different = await ack(env.base, p, draft.draftId, 1, acceptedAck(hash, itemId, 8));
    const lateConflict = await ack(env.base, p, draft.draftId, 1, conflictAck(hash, itemId));
    const row = (await query('SELECT state FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0];
    const acks = (await query(`SELECT count(*)::int AS n FROM relay_ack a JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    assert.equal(wrongHash.body.error.code, 'PAYLOAD_HASH_MISMATCH');
    assert.equal(invalid.body.error.code, 'ACK_INVALID');
    assert.equal(extra.body.error.code, 'ACK_INVALID');
    assert.deepEqual([first.status, first.body.replayed, replay.status, replay.body.replayed], [200, false, 200, true]);
    assert.equal(replay.body.receivedAt, first.body.receivedAt);
    assert.equal(different.body.error.code, 'ACK_MISMATCH');
    assert.deepEqual([lateConflict.status, lateConflict.body.error.code, lateConflict.body.error.currentState], [409, 'STATE_CONFLICT', 'ACCEPTED']);
    assert.deepEqual([row.state, acks], ['ACCEPTED', 1]);
    return { wrongHash: wrongHash.body.error.code, replayed: replay.body.replayed, different: different.body.error.code, lateConflict: lateConflict.body.error.code, finalState: row.state, ackRows: acks };
  });

  scenario('concurrent-ack-cas', { ce: ['CE-19'], grade, expected: 'racing ACCEPTED/REJECTED acks: exactly one wins, every other is 409, identical twins of the winner replay; one TERMINAL row' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const sent = await submit(env.base, p, draft);
    const hash = sent.body.payloadHash;
    const item = randomUUID();
    const bodies = [
      ...Array.from({ length: 4 }, (_, i) => acceptedAck(hash, item, 10 + i)),
      ...Array.from({ length: 4 }, () => rejectedAck(hash, 'USER_DISCARDED')),
    ];
    const results = await Promise.all(bodies.map((body) => ack(env.base, p, draft.draftId, 1, body)));
    const firstApplied = results.filter((r) => r.status === 200 && r.body.replayed === false);
    const replays = results.filter((r) => r.status === 200 && r.body.replayed === true);
    const refused = results.filter((r) => r.status === 409);
    const rows = (await query(`SELECT count(*)::int AS n FROM relay_ack a JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    assert.equal(firstApplied.length, 1);
    assert.equal(rows, 1);
    assert.equal(firstApplied.length + replays.length + refused.length, bodies.length);
    return { applied: firstApplied.length, replays: replays.length, refused: refused.length, ackRows: rows, winnerState: firstApplied[0]!.body.state };
  });
}
