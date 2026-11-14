import assert from 'node:assert/strict';
import { acceptedAck, ack, api, makeDraft, pair, submit } from '../support/client.ts';
import { portIsFree, sleep } from '../support/pg-harness.ts';
import { scenario } from '../support/recorder.ts';
import { startRelay } from '../support/relay-process.ts';
import { DB, env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';

async function seed() {
  const p = await pair(env.base);
  const pendingDraft = makeDraft(p.pairingId);
  const pendingSent = await submit(env.base, p, pendingDraft);
  const ackedDraft = makeDraft(p.pairingId);
  const ackedSent = await submit(env.base, p, ackedDraft);
  await ack(env.base, p, ackedDraft.draftId, 1, acceptedAck(ackedSent.body.payloadHash, ackedDraft.payload.id, 11));
  const revoked = await pair(env.base);
  await api(env.base, 'POST', '/vehicle/v1/pairing/revoke', { token: revoked.vehicleToken });
  return { p, pendingDraft, pendingSent, ackedDraft, ackedSent, revoked };
}

type Seed = Awaited<ReturnType<typeof seed>>;

/** Everything a restart must not lose: pending draft, durable ack, revoke, idempotency and working credentials. */
async function assertRecovered(s: Seed) {
  const list = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: s.p.vehicleToken });
  const status = await api(env.base, 'GET', `/phone/v1/drafts/${s.ackedDraft.draftId}/1`, { token: s.p.phoneToken });
  const replay = await submit(env.base, s.p, s.pendingDraft);
  const revokedStill = await api(env.base, 'GET', '/vehicle/v1/pairing', { token: s.revoked.vehicleToken });
  const facts = {
    pendingListed: list.body.drafts.map((d: { draftId: string }) => d.draftId).includes(s.pendingDraft.draftId),
    pendingHashStable: list.body.drafts.find((d: { draftId: string }) => d.draftId === s.pendingDraft.draftId)?.payloadHash === s.pendingSent.body.payloadHash,
    ackedState: status.body.state,
    ackedRevision: status.body.terminalAck?.acceptedRevision,
    replayStatus: replay.status,
    replayDuplicate: replay.body.duplicate,
    revokedStill: revokedStill.body.error?.code,
  };
  assert.deepEqual(facts, { pendingListed: true, pendingHashStable: true, ackedState: 'ACCEPTED', ackedRevision: 11, replayStatus: 200, replayDuplicate: true, revokedStill: 'PAIRING_REVOKED' });
  return facts;
}

export function restartScenarios(): void {
  scenario('relay-sigterm-restart', { ce: ['CE-20', 'CE-18'], grade, expected: 'SIGTERM exits 0; same port refused while down; after restart pending draft, ack, revoke and idempotency are intact' }, async () => {
    const s = await seed();
    const exit = await env.relay.stop('SIGTERM');
    assert.equal(exit.code, 0);
    const down = await api(env.base, 'GET', '/healthz').then(() => 'reachable', () => 'connection_refused');
    assert.equal(down, 'connection_refused');
    await env.relay.start();
    assert.equal(env.relay.base, env.base);
    return { exit, whileDown: down, recovered: await assertRecovered(s) };
  });

  scenario('relay-sigkill-restart-under-load', { ce: ['CE-20', 'CE-18'], grade, expected: 'SIGKILL during in-flight submits: after restart client retries are idempotent, each key has exactly one row, no partial photo or payload state' }, async () => {
    const s = await seed();
    const load = await pair(env.base);
    const drafts = Array.from({ length: 90 }, () => makeDraft(load.pairingId));
    let delivered = 0;
    const inFlight = Promise.allSettled(drafts.map((draft) => submit(env.base, load, draft).then((r) => {
      delivered += 1;
      return r;
    })));
    // Kill between commits: some requests answered, others still in flight or not yet committed.
    const killBy = Date.now() + 5000;
    while (delivered < 10 && Date.now() < killBy) await sleep(1);
    const exit = await env.relay.stop('SIGKILL');
    assert.equal(exit.signal, 'SIGKILL');
    const firstRound = await inFlight;
    assert.ok(firstRound.some((r) => r.status === 'fulfilled'), 'some commits must precede the kill');
    assert.ok(firstRound.some((r) => r.status === 'rejected'), 'some requests must be cut by the kill');
    await env.relay.start();
    const retried = await Promise.all(drafts.map((draft) => submit(env.base, load, draft)));
    const counts = (await query(`SELECT count(*)::int AS rows, count(DISTINCT draft_id)::int AS keys FROM relay_draft WHERE pairing_id = $1`, [load.pairingId])).rows[0];
    assert.ok(retried.every((r) => r.status === 200 || r.status === 201));
    assert.deepEqual([counts.rows, counts.keys], [90, 90]);
    assert.ok(retried.some((r) => r.status === 200), 'answered requests must replay as duplicates');
    return {
      exit,
      firstRoundDelivered: firstRound.filter((r) => r.status === 'fulfilled').length,
      firstRoundLost: firstRound.filter((r) => r.status === 'rejected').length,
      retried: { created: retried.filter((r) => r.status === 201).length, duplicate: retried.filter((r) => r.status === 200).length },
      counts,
      recovered: await assertRecovered(s),
    };
  });

  scenario('postgres-sigkill-recovery', { ce: ['CE-20', 'CE-18'], grade, expected: 'own postmaster SIGKILL: relay answers typed 503 without internals; after WAL recovery committed data is intact and the same relay process reconnects' }, async () => {
    const s = await seed();
    const pidBefore = env.cluster.postmasterPid();
    await env.cluster.killPostmaster('SIGKILL');
    let down = await api(env.base, 'GET', `/phone/v1/drafts/${s.ackedDraft.draftId}/1`, { token: s.p.phoneToken });
    const deadline = Date.now() + 8000;
    while (down.status !== 503 && Date.now() < deadline) {
      await sleep(100);
      down = await api(env.base, 'GET', `/phone/v1/drafts/${s.ackedDraft.draftId}/1`, { token: s.p.phoneToken });
    }
    assert.equal(down.status, 503);
    assert.deepEqual(Object.keys(down.body.error).sort(), ['code', 'requestId']);
    assert.equal(down.body.error.code, 'DATABASE_UNAVAILABLE');
    const health = await api(env.base, 'GET', '/healthz');
    assert.equal(health.status, 503);
    await env.cluster.start().catch(async () => {
      // Orphaned backends notice postmaster death within moments; shared memory is then reusable.
      await sleep(2000);
      await env.cluster.start();
    });
    const pidAfter = env.cluster.postmasterPid();
    assert.notEqual(pidBefore, pidAfter);
    const recoveryLogged = /database system was not properly shut down|automatic recovery in progress|redo /i.test((await import('node:fs')).readFileSync(env.cluster.logFile, 'utf8'));
    let healthy = await api(env.base, 'GET', '/healthz');
    const until = Date.now() + 8000;
    while (healthy.status !== 200 && Date.now() < until) {
      await sleep(100);
      healthy = await api(env.base, 'GET', '/healthz');
    }
    assert.equal(healthy.status, 200);
    return { pidBefore, pidAfter, whileDown: { status: down.status, code: down.body.error.code }, recoveryLogged, recovered: await assertRecovered(s) };
  });

  scenario('ttl-applies-across-relay-downtime', { ce: ['CE-20'], grade, expected: 'expiry is database time: a draft that lapsed while the relay was down reads EXPIRED right after restart, unexpired data stays' }, async () => {
    const short = await startRelay({ databaseUrl: env.cluster.runtimeUrl(DB), configFile: 'test/fixtures/config-short-ttl.json' });
    env.extra.push(short);
    const p = await pair(short.base);
    const draft = makeDraft(p.pairingId);
    const sent = await submit(short.base, p, draft);
    assert.equal((await short.stop('SIGKILL')).signal, 'SIGKILL');
    await sleep(Math.max(0, new Date(sent.body.expiresAt).getTime() - Date.now() + 200));
    await short.start();
    const status = await api(short.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken });
    const list = await api(short.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    await short.stop();
    assert.deepEqual([status.body.state, list.body.drafts.length], ['EXPIRED', 0]);
    return { stateAfterRestart: status.body.state, vehicleListed: list.body.drafts.length };
  });
}
