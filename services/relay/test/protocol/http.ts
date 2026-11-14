import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { request } from 'node:http';
import { api, CONSENT, jpeg, makeDraft, pair, PHOTO_CONSENT, submit, acceptedAck, ack, exifSegment, secondScan } from '../support/client.ts';
import { sha256Hex } from '../../src/domain/hash.ts';
import { scenario } from '../support/recorder.ts';
import { PHONE_ORIGIN } from '../support/relay-process.ts';
import { env, query } from '../support/suite-env.ts';

const grade = 'real_postgres_local_integration';

const rawPost = (path: string, body: string, headers: Record<string, string>) => fetch(env.base + path, { method: 'POST', body, headers });

/** Streams chunks with no Content-Length and reports how far the server let the upload go. */
function streamUntilStopped(path: string, token: string, chunkBytes: number, maxChunks: number): Promise<{ status: number | null; code?: string; sentBytes: number; closedEarly: boolean }> {
  return new Promise((done) => {
    const url = new URL(env.base + path);
    let sent = 0;
    let finished = false;
    const finish = (result: { status: number | null; code?: string }) => {
      if (finished) return;
      finished = true;
      done({ ...result, sentBytes: sent, closedEarly: sent < chunkBytes * maxChunks });
    };
    const req = request({ host: url.hostname, port: url.port, path: url.pathname, method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' } }, (res) => {
      let text = '';
      res.on('data', (chunk) => { text += chunk; });
      res.on('end', () => finish({ status: res.statusCode ?? null, code: JSON.parse(text || '{}').error?.code }));
      res.on('error', () => finish({ status: res.statusCode ?? null }));
    });
    req.on('error', () => finish({ status: null }));
    const chunk = Buffer.alloc(chunkBytes, 0x20);
    const pump = (index: number) => {
      if (finished || req.destroyed) return;
      if (index >= maxChunks) {
        req.end();
        return;
      }
      sent += chunk.length;
      req.write(chunk, () => setImmediate(() => pump(index + 1)));
    };
    pump(0);
  });
}

export function httpScenarios(): void {
  scenario('malformed-http-input', { ce: ['CE-18'], grade, expected: 'invalid JSON 400, wrong content type 415, no store of anything' }, async () => {
    const p = await pair(env.base);
    const headers = { authorization: `Bearer ${p.phoneToken}`, 'content-type': 'application/json' };
    const invalidJson = await rawPost('/phone/v1/drafts', '{not json', headers);
    const wrongType = await rawPost('/phone/v1/drafts', '{}', { ...headers, 'content-type': 'text/plain' });
    const rows = (await query('SELECT count(*)::int AS n FROM relay_draft WHERE pairing_id = $1', [p.pairingId])).rows[0].n;
    const result = { invalidJson: [invalidJson.status, (await invalidJson.json()).error.code], wrongType: [wrongType.status, (await wrongType.json()).error.code], rows };
    assert.deepEqual(result, { invalidJson: [400, 'INVALID_JSON'], wrongType: [415, 'CONTENT_TYPE_UNSUPPORTED'], rows: 0 });
    return result;
  });

  scenario('body-size-limits-streaming', { grade, expected: 'declared oversize refused before reading; chunked upload without Content-Length is cut once the cap passes; JSON-only body over its own cap refused; ack and claim caps apply; unauthenticated upload is refused before buffering' }, async () => {
    const p = await pair(env.base);
    const headers = { authorization: `Bearer ${p.phoneToken}`, 'content-type': 'application/json' };
    const declared = await rawPost('/phone/v1/drafts', ' '.repeat(3_000_000), headers).then(async (r) => [r.status, (await r.json()).error.code], (cause) => ['transport', String(cause.message)]);
    const stream = await streamUntilStopped('/phone/v1/drafts', p.phoneToken, 256 * 1024, 40);
    const jsonOnly = await api(env.base, 'POST', '/phone/v1/drafts', { token: p.phoneToken, json: { consent: CONSENT, draft: { ...makeDraft(p.pairingId), pad: 'x'.repeat(70_000) } } });
    const ackBody = await rawPost(`/vehicle/v1/drafts/${makeDraft(p.pairingId).draftId}/1/ack`, ' '.repeat(5000), { authorization: `Bearer ${p.vehicleToken}`, 'content-type': 'application/json' }).then((r) => r.status);
    const claimBody = await rawPost('/phone/v1/pairing/claim', ' '.repeat(2000), { 'content-type': 'application/json' }).then((r) => r.status);
    const unauthStream = await streamUntilStopped('/phone/v1/drafts', `ph_${'Z'.repeat(43)}`, 256 * 1024, 40);
    assert.deepEqual(declared, [413, 'BODY_TOO_LARGE']);
    assert.ok(stream.status === 413 || stream.status === null, `stream status ${stream.status}`);
    assert.ok(stream.closedEarly);
    assert.deepEqual([jsonOnly.status, jsonOnly.body.error.code], [413, 'BODY_TOO_LARGE']);
    assert.deepEqual([ackBody, claimBody], [413, 413]);
    assert.ok(unauthStream.status === 401 || unauthStream.status === null);
    return { declared, stream, jsonOnly: jsonOnly.body.error.code, ackBody, claimBody, unauthStream };
  });

  scenario('url-bearer-refused', { ce: ['CE-18'], grade, expected: 'token in query or path, even with a valid token, is 400 URL_BEARER_FORBIDDEN and never authenticates' }, async () => {
    const p = await pair(env.base);
    const results = {
      queryToken: await api(env.base, 'GET', `/vehicle/v1/drafts?token=${p.vehicleToken}`),
      accessToken: await api(env.base, 'GET', `/vehicle/v1/drafts?access_token=abc`),
      anyQueryValue: await api(env.base, 'GET', `/vehicle/v1/drafts?limit=${p.vehicleToken}`),
      encoded: await api(env.base, 'GET', `/vehicle/v1/drafts?x=${encodeURIComponent(p.vehicleToken)}`),
      pathToken: await api(env.base, 'GET', `/vehicle/v1/${p.vehicleToken}/drafts`),
      authWithQuery: await api(env.base, 'GET', `/vehicle/v1/drafts?bearer=abc`, { token: p.vehicleToken }),
      unknownQuery: await api(env.base, 'GET', `/vehicle/v1/drafts?foo=1`, { token: p.vehicleToken }),
    };
    const codes = Object.fromEntries(Object.entries(results).map(([k, r]) => [k, [r.status, r.body.error.code]]));
    assert.deepEqual(codes, {
      queryToken: [400, 'URL_BEARER_FORBIDDEN'], accessToken: [400, 'URL_BEARER_FORBIDDEN'], anyQueryValue: [400, 'URL_BEARER_FORBIDDEN'], encoded: [400, 'URL_BEARER_FORBIDDEN'],
      pathToken: [400, 'URL_BEARER_FORBIDDEN'], authWithQuery: [400, 'URL_BEARER_FORBIDDEN'], unknownQuery: [400, 'QUERY_INVALID'],
    });
    return codes;
  });

  scenario('response-security-headers-and-cors', { grade, expected: 'every response no-store/no-referrer/nosniff; only the phone origin on phone routes passes CORS; vehicle routes refuse browser origins' }, async () => {
    const p = await pair(env.base);
    const draft = makeDraft(p.pairingId);
    const ok = await submit(env.base, p, draft);
    const bad = await api(env.base, 'GET', '/nope');
    const headersOf = (r: { headers: Headers }) => ({ cache: r.headers.get('cache-control'), referrer: r.headers.get('referrer-policy'), nosniff: r.headers.get('x-content-type-options') });
    const expectedHeaders = { cache: 'no-store', referrer: 'no-referrer', nosniff: 'nosniff' };
    assert.deepEqual(headersOf(ok), expectedHeaders);
    assert.deepEqual(headersOf(bad), expectedHeaders);
    const preflight = await fetch(`${env.base}/phone/v1/drafts`, { method: 'OPTIONS', headers: { origin: PHONE_ORIGIN, 'access-control-request-method': 'POST' } });
    const fromPhone = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken, headers: { origin: PHONE_ORIGIN } });
    const fromEvil = await api(env.base, 'GET', `/phone/v1/drafts/${draft.draftId}/1`, { token: p.phoneToken, headers: { origin: 'https://evil.invalid' } });
    const vehicleBrowser = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken, headers: { origin: PHONE_ORIGIN } });
    const result = {
      preflight: [preflight.status, preflight.headers.get('access-control-allow-origin')],
      fromPhone: [fromPhone.status, fromPhone.headers.get('access-control-allow-origin')],
      fromEvil: [fromEvil.status, fromEvil.body.error.code, fromEvil.headers.get('access-control-allow-origin')],
      vehicleBrowser: [vehicleBrowser.status, vehicleBrowser.body.error.code],
    };
    assert.deepEqual(result, {
      preflight: [204, PHONE_ORIGIN], fromPhone: [200, PHONE_ORIGIN], fromEvil: [403, 'ORIGIN_FORBIDDEN', null], vehicleBrowser: [403, 'ORIGIN_FORBIDDEN'],
    });
    return { headers: expectedHeaders, ...result };
  });

  scenario('photo-minimal-receive-roundtrip', { grade, expected: 'photo stored only with opt-in, readable only by the same pairing vehicle as an attachment, no public URL, gone right after a terminal ack; EXIF photo refused over HTTP' }, async () => {
    const p = await pair(env.base);
    const other = await pair(env.base);
    const bytes = jpeg();
    const photoSha = sha256Hex(bytes);
    const draft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${photoSha}` } });
    const photo = { mime: 'image/jpeg', dataBase64: bytes.toString('base64') };
    const noOptIn = await submit(env.base, p, draft, CONSENT, photo);
    const sent = await submit(env.base, p, draft, PHOTO_CONSENT, photo);
    const list = await api(env.base, 'GET', '/vehicle/v1/drafts', { token: p.vehicleToken });
    const path = `/vehicle/v1/drafts/${draft.draftId}/1/photo`;
    const read = await fetch(env.base + path, { headers: { authorization: `Bearer ${p.vehicleToken}` } });
    const body = Buffer.from(await read.arrayBuffer());
    const anon = await api(env.base, 'GET', path);
    const crossPair = await api(env.base, 'GET', path, { token: other.vehicleToken });
    const phoneRead = await api(env.base, 'GET', path, { token: p.phoneToken });
    await ack(env.base, p, draft.draftId, 1, acceptedAck(sent.body.payloadHash, draft.payload.id));
    const after = await api(env.base, 'GET', path, { token: p.vehicleToken });
    const left = (await query(`SELECT count(*)::int AS n FROM relay_draft_photo ph JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1`, [p.pairingId])).rows[0].n;
    const exifBytes = jpeg({ segments: [exifSegment()] });
    const exifDraft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${sha256Hex(exifBytes)}` } });
    const exif = await submit(env.base, p, exifDraft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: exifBytes.toString('base64') });
    const result = {
      noOptIn: noOptIn.body.error.code,
      listedMeta: list.body.drafts[0].photo,
      bytesMatch: body.equals(bytes),
      disposition: read.headers.get('content-disposition'),
      anon: anon.status,
      crossPair: crossPair.body.error.code,
      phoneRead: phoneRead.body.error.code,
      afterTerminal: after.body.error.code,
      photoRowsLeft: left,
      exif: [exif.status, exif.body.error.code, exif.body.error.metadata],
    };
    assert.deepEqual(result, {
      noOptIn: 'PHOTO_CONSENT_REQUIRED',
      listedMeta: { mime: 'image/jpeg', sha256: photoSha, byteLength: bytes.length, width: 640, height: 480 },
      bytesMatch: true, disposition: 'attachment', anon: 401, crossPair: 'PHOTO_NOT_FOUND', phoneRead: 'ROLE_FORBIDDEN', afterTerminal: 'PHOTO_NOT_FOUND', photoRowsLeft: 0,
      exif: [422, 'PHOTO_METADATA_FORBIDDEN', 'JPEG_APP1'],
    });
    return result;
  });

  scenario('photo-metadata-any-position-over-http', { ce: ['CE-18'], grade, expected: 'APP1/comment after the first scan, between scans and after EOI are refused before storage for a real encoded image as well as synthetic ones; nothing is stored or fetchable; real two-scan and Chrome canvas images pass and round-trip byte-identical' }, async () => {
    const p = await pair(env.base);
    const canvas = readFileSync(new URL('../fixtures/canvas-chrome.jpg', import.meta.url));
    const body = canvas.subarray(0, canvas.length - 2);
    const eoi = canvas.subarray(canvas.length - 2);
    const bad: Record<string, Buffer> = {
      realJpegApp1AfterScan: Buffer.concat([body, exifSegment(), eoi]),
      realJpegCommentAfterScan: Buffer.concat([body, Buffer.from([0xff, 0xfe, 0, 4, 65, 66]), eoi]),
      realJpegDataAfterEoi: Buffer.concat([canvas, exifSegment()]),
      app1BetweenScans: jpeg({ afterFirstScan: [exifSegment(), secondScan()] }),
      app1AfterSecondScan: jpeg({ afterFirstScan: [secondScan(), exifSegment()] }),
    };
    const send = (bytes: Buffer) => {
      const draft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${sha256Hex(bytes)}` } });
      return submit(env.base, p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: bytes.toString('base64') }).then((r) => ({ draft, r }));
    };
    const refused: Record<string, unknown> = {};
    for (const [name, bytes] of Object.entries(bad)) {
      const { r } = await send(bytes);
      refused[name] = [r.status, r.body.error?.code];
    }
    const storedAfterRefusals = (await query(`SELECT (SELECT count(*)::int FROM relay_draft WHERE pairing_id = $1) AS drafts, (SELECT count(*)::int FROM relay_draft_photo ph JOIN relay_draft d USING (draft_pk) WHERE d.pairing_id = $1) AS photos`, [p.pairingId])).rows[0];
    assert.deepEqual(refused, {
      realJpegApp1AfterScan: [422, 'PHOTO_METADATA_FORBIDDEN'], realJpegCommentAfterScan: [422, 'PHOTO_METADATA_FORBIDDEN'], realJpegDataAfterEoi: [422, 'PHOTO_MALFORMED'],
      app1BetweenScans: [422, 'PHOTO_METADATA_FORBIDDEN'], app1AfterSecondScan: [422, 'PHOTO_METADATA_FORBIDDEN'],
    });
    assert.deepEqual(storedAfterRefusals, { drafts: 0, photos: 0 });
    const accepted: Record<string, unknown> = {};
    for (const [name, bytes] of Object.entries({ chromeCanvas: canvas, twoScans: jpeg({ afterFirstScan: [secondScan()] }) })) {
      const { draft, r } = await send(bytes);
      assert.equal(r.status, 201);
      const read = await fetch(`${env.base}/vehicle/v1/drafts/${draft.draftId}/1/photo`, { headers: { authorization: `Bearer ${p.vehicleToken}` } });
      const same = Buffer.from(await read.arrayBuffer()).equals(bytes);
      assert.ok(same);
      accepted[name] = { submit: r.status, fetch: read.status, byteIdentical: same };
    }
    return { refused, storedAfterRefusals, accepted, boundary: 'canvas output of desktop Chrome 156 with injected segments; synthetic pixels; no mobile browser run' };
  });

  scenario('photo-differs-is-content-conflict', { ce: ['CE-18'], grade, expected: 'same draft key with a different photo is a content conflict, because the hash covers the photo digest' }, async () => {
    const p = await pair(env.base);
    const a = jpeg({ width: 640 });
    const b = jpeg({ width: 320 });
    const draft = makeDraft(p.pairingId, { payload: { photoRef: `sha256:${sha256Hex(a)}` } });
    await submit(env.base, p, draft, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: a.toString('base64') });
    const changed = { ...draft, payload: { ...draft.payload, photoRef: `sha256:${sha256Hex(b)}` } };
    const conflict = await submit(env.base, p, changed, PHOTO_CONSENT, { mime: 'image/jpeg', dataBase64: b.toString('base64') });
    assert.equal(conflict.body.error.code, 'DRAFT_CONTENT_CONFLICT');
    return { conflict: conflict.body.error.code };
  });
}
