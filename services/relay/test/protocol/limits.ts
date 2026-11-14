import assert from 'node:assert/strict';
import { api, iccSegment, jpeg, makeDraft, pair, PHOTO_CONSENT, submit } from '../support/client.ts';
import { sha256Hex } from '../../src/domain/hash.ts';
import { scenario } from '../support/recorder.ts';
import { migrate, startRelay, startupOutcome, type RelayProcess } from '../support/relay-process.ts';
import { env } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';
const TIGHT_DB = 'cargo_relay_tight_test';
const TIGHT = 'test/fixtures/config-tight.json';

export function limitScenarios(): void {
  let relay: RelayProcess;

  scenario('tight-relay-start', { grade, expected: 'relay on a dedicated database with low limits starts' }, async () => {
    await env.cluster.createDatabase(TIGHT_DB);
    assert.equal(migrate(env.cluster, TIGHT_DB, TIGHT).status, 0);
    relay = await startRelay({ databaseUrl: env.cluster.runtimeUrl(TIGHT_DB), configFile: TIGHT });
    env.extra.push(relay);
    return { port: relay.port };
  });

  scenario('pairing-draft-count-byte-limits', { grade, expected: 'active-draft cap gives 429 with Retry-After, active-byte cap gives 413, and a list page is capped' }, async () => {
    const p = await pair(relay.base);
    const statuses: number[] = [];
    for (let i = 0; i < 3; i += 1) statuses.push((await submit(relay.base, p, makeDraft(p.pairingId))).status);
    const overflow = await submit(relay.base, p, makeDraft(p.pairingId));
    const page = await api(relay.base, 'GET', '/vehicle/v1/drafts?limit=99', { token: p.vehicleToken });
    const pageDefault = await api(relay.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });

    const q = await pair(relay.base);
    const big = jpeg({ segments: [iccSegment(0x0ef0)] });
    const bigDraft = () => makeDraft(q.pairingId, { payload: { photoRef: `sha256:${sha256Hex(big)}` } });
    const photo = { mime: 'image/jpeg', dataBase64: big.toString('base64') };
    const firstBig = await submit(relay.base, q, bigDraft(), PHOTO_CONSENT, photo);
    const secondBig = await submit(relay.base, q, bigDraft(), PHOTO_CONSENT, photo);
    const tooBigBytes = jpeg({ segments: [iccSegment(0x1ff0)] });
    const tooBig = await submit(relay.base, q, makeDraft(q.pairingId, { payload: { photoRef: `sha256:${sha256Hex(tooBigBytes)}` } }), PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: tooBigBytes.toString('base64') });
    const result = {
      statuses,
      overflow: [overflow.status, overflow.body.error.code, overflow.headers.get('retry-after')],
      pageTooLarge: [page.status, page.body.error?.code],
      pageDefaultLength: pageDefault.body.drafts.length,
      firstBig: firstBig.status,
      secondBig: [secondBig.status, secondBig.body.error?.code],
      tooBig: [tooBig.status, tooBig.body.error?.code],
    };
    assert.deepEqual(result, {
      statuses: [201, 201, 201],
      overflow: [429, 'PAIRING_DRAFT_LIMIT', '60'],
      pageTooLarge: [400, 'QUERY_INVALID'],
      pageDefaultLength: 2,
      firstBig: 201,
      secondBig: [413, 'PAIRING_BYTES_LIMIT'],
      tooBig: [413, 'PHOTO_TOO_LARGE'],
    });
    return result;
  });

  scenario('active-pairing-cap-and-rate-limit', { grade, expected: 'global active-pairing cap refuses a further pairing with 429; request bursts on one credential or address get 429 with Retry-After; no IP or user agent is persisted' }, async () => {
    // Two pairings already exist from the previous case; the cap is 4.
    const third = await api(relay.base, 'POST', '/vehicle/v1/pairings');
    const fourth = await api(relay.base, 'POST', '/vehicle/v1/pairings');
    const capped = await api(relay.base, 'POST', '/vehicle/v1/pairings');
    assert.deepEqual([third.status, fourth.status, capped.status, capped.body.error.code], [201, 201, 429, 'ACTIVE_PAIRING_LIMIT']);

    let limited = null as Awaited<ReturnType<typeof api>> | null;
    let attempts = 0;
    while (!limited && attempts < 120) {
      attempts += 1;
      const r = await api(relay.base, 'GET', '/vehicle/v1/pairing', { token: third.body.vehicleToken });
      if (r.status === 429) limited = r;
    }
    assert.ok(limited);
    assert.equal(limited.body.error.code, 'RATE_LIMITED');
    assert.ok(Number(limited.headers.get('retry-after')) > 0);

    let unauthAttempts = 0;
    let unauthLimited = false;
    while (!unauthLimited && unauthAttempts < 30) {
      unauthAttempts += 1;
      const r = await api(relay.base, 'GET', '/vehicle/v1/pairing', { token: `vh_${String(unauthAttempts).padStart(43, 'Q')}` });
      unauthLimited = r.status === 429;
    }
    assert.ok(unauthLimited);

    const columns = await env.cluster.admin(async (client) => (await client.query<{ column_name: string }>(`SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'`)).rows.map((row) => row.column_name), TIGHT_DB);
    assert.equal(columns.filter((c) => /(^|_)(ip|addr|address|user_agent|ua)($|_)/i.test(c)).length, 0);
    return { capped: capped.body.error.code, authenticatedLimitedAfter: attempts, badTokenLimitedAfter: unauthAttempts, ipOrUaColumns: 0 };
  });

  scenario('startup-refusals-as-real-process', { grade, expected: 'the actual entrypoint exits nonzero for cleartext on non-loopback and for a test profile outside demo-local, and refuses a configuration file differing from the seeded version row' }, async () => {
    const base = {
      RELAY_DATABASE_URL: env.cluster.runtimeUrl(TIGHT_DB),
      PHONE_WEB_BASE_URL: 'http://127.0.0.1:43001/', PHONE_RELAY_BASE_URL: 'http://127.0.0.1:43002/', VEHICLE_RELAY_BASE_URL: 'http://10.0.2.2:43002/',
      RELAY_PORT: '0',
    };
    const lan = startupOutcome({ ...base, RELAY_DEMO_LOCAL: '1', RELAY_BIND_HOST: '0.0.0.0' });
    const noDemo = startupOutcome(base);
    const unseeded = startupOutcome({ ...base, RELAY_DEMO_LOCAL: '1' }); // production config version 1 is not seeded in this DB
    assert.ok(lan.refused && noDemo.refused && unseeded.refused);
    assert.match(lan.message, /refusing cleartext/);
    assert.match(noDemo.message, /refusing cleartext/);
    assert.match(unseeded.message, /not seeded/);
    return { lan: lan.message, noDemo: noDemo.message, unseeded: unseeded.message };
  });

  scenario('tight-relay-stop', { grade, expected: 'relay stops cleanly' }, async () => {
    assert.equal((await relay.stop()).code, 0);
    return { stopped: true };
  });

}
