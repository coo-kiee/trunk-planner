import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { api, iccSegment, jpeg, makeDraft, pair, PHOTO_CONSENT, submit } from '../support/client.ts';
import { sha256Hex } from '../../src/domain/hash.ts';
import { scenario, type Grade } from '../support/recorder.ts';
import { capturedRelayOutput } from '../support/relay-process.ts';
import { env, query } from '../support/suite-env.ts';

function filesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = resolve(dir, name);
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

/** Verifies no secret reached any log channel, and that plaintext bearers reached neither tables nor on-disk data files. */
export function redactionScenario(grade: Grade = 'real_postgres_local_integration'): void {
  scenario('log-and-storage-redaction', { ce: ['CE-18'], grade, expected: 'sentinel name, QR/vehicle/phone tokens and photo bytes appear in no relay stdout/stderr or PostgreSQL log; plaintext tokens appear in no table and no file of the data directory; a request URL carrying a token is not logged' }, async () => {
    const p = await pair(env.base);
    const name = `SENTINEL-NAME-${randomUUID()}`;
    const photoMarker = `PHOTOSENTINEL${randomUUID().replaceAll('-', '')}`;
    const marked = jpeg({ segments: [iccSegment(Buffer.from(photoMarker))] });
    const draft = makeDraft(p.pairingId, { payload: { name, photoRef: `sha256:${sha256Hex(marked)}` } });
    const sent = await submit(env.base, p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: marked.toString('base64') });
    assert.equal(sent.status, 201);
    // Failure paths are where naive logging leaks: invalid body, content conflict, token in URL.
    await submit(env.base, p, { ...draft, payload: { ...draft.payload, errorMm: 1 } });
    await submit(env.base, p, { ...draft, payload: { ...draft.payload, name: 'x'.repeat(500), extra: name } });
    await api(env.base, 'GET', `/vehicle/v1/drafts?access_token=${p.vehicleToken}`);
    await api(env.base, 'GET', `/vehicle/v1/${p.phoneToken}/drafts`);
    const replay = await api(env.base, 'POST', '/phone/v1/pairing/claim', { json: { pairingToken: p.pairingToken } });
    assert.equal(replay.status, 409);
    await env.cluster.admin((client) => client.query('CHECKPOINT'));

    const secrets = { qrToken: p.pairingToken, vehicleToken: p.vehicleToken, phoneToken: p.phoneToken };
    const relayLog = capturedRelayOutput.join('');
    const pgLog = readFileSync(env.cluster.logFile, 'utf8');
    const photoBase64 = marked.toString('base64');
    const dataBlobs = filesUnder(env.cluster.dataDir).map((path) => readFileSync(path));
    const leaks: string[] = [];
    for (const [label, value] of Object.entries({ ...secrets, name, photoMarker, photoBase64 })) {
      if (relayLog.includes(value)) leaks.push(`relay-log:${label}`);
      if (pgLog.includes(value)) leaks.push(`pg-log:${label}`);
    }
    for (const [label, value] of Object.entries(secrets)) {
      if (dataBlobs.some((blob) => blob.includes(value))) leaks.push(`data-dir:${label}`);
    }
    const tableText = (await query(`SELECT string_agg(t::text, ' ') AS text FROM (
        SELECT encode(token_digest, 'hex') AS t FROM relay_credential
        UNION ALL SELECT payload::text FROM relay_draft WHERE payload IS NOT NULL
        UNION ALL SELECT body::text FROM relay_ack) x`)).rows[0].text as string;
    for (const [label, value] of Object.entries(secrets)) {
      if (tableText.includes(value)) leaks.push(`table:${label}`);
    }
    const logEvents = relayLog.split('\n').filter((line) => line.startsWith('{')).map((line) => JSON.parse(line));
    const fieldSet = [...new Set(logEvents.flatMap((event) => Object.keys(event)))].sort();
    assert.deepEqual(leaks, []);
    assert.ok(logEvents.length > 100);
    return {
      leaks,
      scannedRelayLogBytes: relayLog.length,
      scannedPgLogBytes: pgLog.length,
      scannedDataFiles: dataBlobs.length,
      logEvents: logEvents.length,
      observedLogFields: fieldSet,
      note: 'item content is expected in active payload rows only; deletion does not physically scrub dead tuples before vacuum.',
    };
  });
}
