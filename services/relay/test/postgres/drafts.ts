import assert from 'node:assert/strict';
import { acceptedAck, ack, api, makeDraft, pair, submit, CONSENT } from '../support/client.ts';
import { scenario } from '../support/recorder.ts';
import { env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';

export function draftScenarios(): void {
  scenario('duplicate-is-same-receipt', { ce: ['CE-18'], grade, expected: 'same (pairing,draft,clientRevision) with same content returns the original receipt; one row; key order does not change the hash' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const first = await submit(env.base, p, draft);
    const reordered = Object.fromEntries(Object.entries(draft).reverse());
    const again = await submit(env.base, p, reordered);
    const rows = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    assert.deepEqual([first.status, again.status, rows], [201, 200, 1]);
    assert.equal(again.body.duplicate, true);
    assert.equal(again.body.payloadHash, first.body.payloadHash);
    assert.equal(again.body.receivedAt, first.body.receivedAt);
    return { firstStatus: first.status, replayStatus: again.status, rows, sameHash: true };
  });

  scenario('same-key-different-content-409', { ce: ['CE-18'], grade, expected: 'same key with different content is DRAFT_CONTENT_CONFLICT and stored content is unchanged' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const first = await submit(env.base, p, draft);
    const changed = { ...draft, payload: { ...draft.payload, errorMm: 9 } };
    const conflict = await submit(env.base, p, changed);
    const stored = (await query('SELECT payload_sha256, payload FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows;
    assert.equal(conflict.status, 409);
    assert.equal(conflict.body.error.code, 'DRAFT_CONTENT_CONFLICT');
    assert.equal(stored.length, 1);
    assert.equal(stored[0].payload_sha256, first.body.payloadHash);
    assert.equal(stored[0].payload.payload.errorMm, 5);
    return { conflict: conflict.body.error.code, rows: stored.length, storedErrorMm: stored[0].payload.payload.errorMm };
  });

  scenario('unique-constraint-direct-sql', { ce: ['CE-18'], grade, expected: 'PostgreSQL itself refuses a duplicate key with SQLSTATE 23505, independent of application checks' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    await submit(env.base, p, draft);
    const code = await query(
      `INSERT INTO relay_draft (pairing_id, draft_id, client_revision, payload_sha256, state, payload, payload_bytes, consent_version, dimensions_consent, photo_consent, received_at, expires_at)
       SELECT pairing_id, draft_id, client_revision, payload_sha256, state, payload, payload_bytes, consent_version, dimensions_consent, photo_consent, received_at, expires_at
         FROM relay_draft WHERE pairing_id = $1`, [p.pairingId]).then(() => 'no error', (cause: { code: string }) => cause.code);
    const terminalWithPayload = await query(
      `UPDATE relay_draft SET state = 'ACCEPTED', terminal_at = clock_timestamp() WHERE pairing_id = $1`, [p.pairingId]).then(() => 'no error', (cause: { code: string; constraint: string }) => `${cause.code}:${cause.constraint}`);
    assert.equal(code, '23505');
    assert.equal(terminalWithPayload, '23514:relay_draft_payload_only_while_active');
    return { duplicateInsert: code, terminalWithPayload };
  });

  scenario('concurrent-duplicates-one-row', { ce: ['CE-18'], grade, expected: '10 parallel identical submits: exactly one 201, nine 200 duplicates, one row' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const results = await Promise.all(Array.from({ length: 10 }, () => submit(env.base, p, draft)));
    const statuses = results.map((r) => r.status).sort();
    const rows = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    assert.equal(statuses.filter((s) => s === 201).length, 1);
    assert.equal(statuses.filter((s) => s === 200).length, 9);
    assert.equal(rows, 1);
    assert.equal(new Set(results.map((r) => r.body.payloadHash)).size, 1);
    return { created: 1, duplicates: 9, rows };
  });

  scenario('supersede-and-stale-revision', { ce: ['CE-18', 'CE-19'], grade, expected: 'N+1 supersedes active N and purges it; late unseen lower revision is STALE; an ack for fetched N cannot change N+1' }, async () => {
    const p = await pair(env.base);
    const draftId = makeDraft(p.pairingId).draftId;
    const n2 = makeDraft(p.pairingId, { draftId, clientRevision: 2 });
    const n3 = makeDraft(p.pairingId, { draftId, clientRevision: 3 });
    const n1 = makeDraft(p.pairingId, { draftId, clientRevision: 1 });
    const sent2 = await submit(env.base, p, n2);
    const fetched = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    const sent3 = await submit(env.base, p, n3);
    const late = await submit(env.base, p, n1);
    const replay2 = await submit(env.base, p, n2);
    const lateAck = await ack(env.base, p, draftId, 2, acceptedAck(sent2.body.payloadHash, n2.payload.id));
    const list = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    const rows = (await query(
      `SELECT client_revision::int AS client_revision, state, payload IS NULL AS purged FROM relay_draft WHERE pairing_id = $1 ORDER BY client_revision`, [p.pairingId])).rows;
    assert.equal(fetched.body.drafts[0].clientRevision, 2);
    assert.equal(sent3.status, 201);
    assert.deepEqual([late.status, late.body.error.code, late.body.error.newestClientRevision], [409, 'STALE_CLIENT_REVISION', 3]);
    assert.deepEqual([replay2.status, replay2.body.state], [200, 'SUPERSEDED']);
    assert.deepEqual([lateAck.status, lateAck.body.error.code, lateAck.body.error.currentState], [409, 'STATE_CONFLICT', 'SUPERSEDED']);
    assert.deepEqual(list.body.drafts.map((d: { clientRevision: number }) => d.clientRevision), [3]);
    assert.deepEqual(rows, [
      { client_revision: 2, state: 'SUPERSEDED', purged: true },
      { client_revision: 3, state: 'PENDING', purged: false },
    ]);
    return { late: late.body.error.code, replayOfSuperseded: replay2.body.state, ackForFetchedN: lateAck.body.error.code, rows };
  });

  scenario('new-revision-after-terminal-keeps-history-minimal', { ce: ['CE-18'], grade, expected: 'after ACCEPTED, the same revision cannot be re-applied; a new clientRevision is a new pending draft and the accepted row keeps no content' }, async () => {
    const p = await pair(env.base);
    const n1 = makeDraft(p.pairingId);
    const sent = await submit(env.base, p, n1);
    await ack(env.base, p, n1.draftId, 1, acceptedAck(sent.body.payloadHash, n1.payload.id));
    const replay = await submit(env.base, p, n1);
    const n2 = makeDraft(p.pairingId, { draftId: n1.draftId, clientRevision: 2 });
    const next = await submit(env.base, p, n2);
    const rows = (await query(`SELECT state, payload IS NULL AS purged FROM relay_draft WHERE pairing_id = $1 ORDER BY client_revision`, [p.pairingId])).rows;
    assert.deepEqual([replay.status, replay.body.state, next.status], [200, 'ACCEPTED', 201]);
    assert.deepEqual(rows, [{ state: 'ACCEPTED', purged: true }, { state: 'PENDING', purged: false }]);
    return { replayState: replay.body.state, next: next.status, rows };
  });

  scenario('no-scene-writer', { ce: ['CE-19'], grade, expected: 'scene/full Envelope is refused; the route inventory and database have no scene or vehicle-control write path' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const sceneInEnvelope = await api(env.base, 'POST', '/phone/v1/drafts', { token: p.phoneToken, json: { consent: CONSENT, draft, scene: { revision: 1 } } });
    const sceneInDraft = await submit(env.base, p, { ...draft, scene: { revision: 1 } });
    const { routes } = await import('../../src/http/server.ts');
    const templates = routes.map((route) => `${route.method} ${route.template}`);
    const tables = (await query(`SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'`)).rows.map((row) => row.table_name);
    assert.equal(sceneInEnvelope.body.error.code, 'ENVELOPE_FIELD_FORBIDDEN');
    assert.equal(sceneInDraft.body.error.code, 'DRAFT_SCHEMA_INVALID');
    assert.equal(templates.filter((t) => /scene|instance|control/i.test(t)).length, 0);
    assert.equal(tables.filter((t) => /scene|instance/i.test(t)).length, 0);
    return { sceneInEnvelope: sceneInEnvelope.body.error.code, sceneInDraft: sceneInDraft.body.error.code, routes: templates, tables };
  });
}
