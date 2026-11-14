import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { assertProfileAllowed, loadConfig, readEnv } from '../../src/config.ts';
import { parseAck } from '../../src/domain/ack.ts';
import { RelayError } from '../../src/domain/errors.ts';
import { canonicalHash, sha256Hex } from '../../src/domain/hash.ts';
import { inspectPhoto } from '../../src/domain/photo-policy.ts';
import { parseSubmission } from '../../src/domain/submission.ts';
import { digestToken, isTokenShaped, issueToken } from '../../src/domain/tokens.ts';
import { RateLimiter } from '../../src/http/rate-limit.ts';
import { routes } from '../../src/http/server.ts';
import { acceptedAck, CONSENT, conflictAck, exifSegment, iccSegment, jpeg, makeDraft, PHOTO_CONSENT, rejectedAck, secondScan, webp } from '../support/client.ts';
import { scenario } from '../support/recorder.ts';

const grade = 'unit';
const config = loadConfig();
const limits = config.limits;

const fixture = (name: string) => readFileSync(new URL(`../fixtures/${name}`, import.meta.url));
const chromeJpeg = fixture('canvas-chrome.jpg');
const chromeWebp = fixture('canvas-chrome.webp');
function webpWithTrailing(): Buffer {
  const file = Buffer.concat([webp(), Buffer.from('XXXX')]);
  file.writeUInt32LE(file.length - 8, 4); // RIFF size stays consistent, leftover is shorter than a chunk header
  return file;
}

const codeOf = (work: () => unknown): string => {
  try {
    work();
    return 'accepted';
  } catch (cause) {
    assert.ok(cause instanceof RelayError, String(cause));
    return cause.code;
  }
};

const submission = (envelope: unknown, bytes = 1000) => parseSubmission(envelope, bytes, config);
const envelope = (overrides: Record<string, unknown> = {}) => ({ consent: CONSENT, draft: makeDraft(randomUUID()), ...overrides });

export function unitScenarios(): void {
  scenario('config-consumes-contract-ttl', { grade, expected: 'versioned relay config reads 300/86400/86400 from packages/contracts configuration-v1 and credential lifetime 86400' }, () => {
    assert.deepEqual(config.ttl, { pairing: 300, draft: 86400, ack: 86400 });
    assert.equal(config.credentialTtlSeconds, 86400);
    assert.equal(config.profile, 'production');
    return { ttl: config.ttl, credentialTtlSeconds: config.credentialTtlSeconds, relayConfigVersion: config.relayConfigVersion, sha256: config.sha256 };
  });

  scenario('token-format-entropy', { grade, expected: 'role-prefixed 32-byte CSPRNG tokens, unique, digest is SHA-256; implementation uses node:crypto only' }, () => {
    const tokens = Array.from({ length: 200 }, () => issueToken('PHONE').token);
    assert.equal(new Set(tokens).size, 200);
    assert.ok(tokens.every(isTokenShaped));
    const { token, digest } = issueToken('VEHICLE');
    assert.ok(digestToken(token).equals(digest));
    assert.equal(Buffer.from(token.slice(3), 'base64url').length, 32);
    assert.equal(isTokenShaped(`xx_${'A'.repeat(43)}`), false);
    return { unique: 200, rawBytes: 32, digestBytes: digest.length };
  });

  scenario('jcs-rfc8785-golden', { grade, expected: 'canonicalization matches the RFC 8785 ordering/number golden and ignores key order' }, () => {
    const golden = '{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\\u000f\\nA\'B\\"\\\\\\\\\\"/"}';
    const input = JSON.parse('{"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],"string":"\\u20ac$\\u000F\\u000aA\'\\u0042\\u0022\\u005c\\\\\\"\\/","literals":[null,true,false]}');
    assert.equal(canonicalHash(input), sha256Hex(golden));
    assert.equal(canonicalHash({ a: 1, b: 2 }), canonicalHash({ b: 2, a: 1 }));
    return { goldenSha256: sha256Hex(golden) };
  });

  scenario('photo-policy-allow-and-reject', { grade, expected: 'allowlisted minimal JPEG/WebP pass; EXIF, comment, unknown APP, wrong magic, disallowed type, animation, oversize bytes and dimensions fail with typed codes' }, () => {
    const cases: Record<string, string> = {
      okJpeg: codeOf(() => inspectPhoto('image/jpeg', jpeg(), limits)),
      okWebp: codeOf(() => inspectPhoto('image/webp', webp(), limits)),
      jpegExif: codeOf(() => inspectPhoto('image/jpeg', jpeg({ segments: [exifSegment()] }), limits)),
      jpegComment: codeOf(() => inspectPhoto('image/jpeg', jpeg({ segments: [Buffer.from([0xff, 0xfe, 0, 4, 65, 66])] }), limits)),
      jpegApp1AfterScan: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [exifSegment()] }), limits)),
      jpegApp1BetweenScans: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [exifSegment(), secondScan()] }), limits)),
      jpegApp1AfterSecondScan: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [secondScan(), exifSegment()] }), limits)),
      jpegCommentAfterScan: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [Buffer.from([0xff, 0xfe, 0, 4, 65, 66])] }), limits)),
      jpegAllowedAppAfterScan: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [iccSegment(4)] }), limits)),
      jpegApp0WrongId: codeOf(() => inspectPhoto('image/jpeg', jpeg({ segments: [Buffer.from([0xff, 0xe0, 0, 6, 69, 120, 105, 102])] }), limits)),
      jpegApp2Mpf: codeOf(() => inspectPhoto('image/jpeg', jpeg({ segments: [Buffer.from([0xff, 0xe2, 0, 6, 77, 80, 70, 0])] }), limits)),
      jpegTwoScansOk: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [secondScan()] }), limits)),
      jpegAfterEoi: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterEoi: exifSegment() }), limits)),
      jpegSecondImageAfterEoi: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterEoi: jpeg() }), limits)),
      jpegNoEoi: codeOf(() => inspectPhoto('image/jpeg', jpeg().subarray(0, jpeg().length - 2), limits)),
      jpegUnknownMarker: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [Buffer.from([0xff, 0xf0, 0, 4, 65, 66])] }), limits)),
      stuffedApp1BytesInScan: codeOf(() => inspectPhoto('image/jpeg', jpeg({ afterFirstScan: [Buffer.from([0x11, 0xff, 0x00, 0xe1, 0xff, 0xd3, 0x22])] }), limits)),
      chromeCanvasJpeg: codeOf(() => inspectPhoto('image/jpeg', chromeJpeg, limits)),
      chromeCanvasWebp: codeOf(() => inspectPhoto('image/webp', chromeWebp, limits)),
      webpUnknownChunk: codeOf(() => inspectPhoto('image/webp', webp({ extra: [['JUNK', Buffer.from('GPS!')]] }), limits)),
      webpTrailingGarbage: codeOf(() => inspectPhoto('image/webp', webpWithTrailing(), limits)),
      jpegApp13: codeOf(() => inspectPhoto('image/jpeg', jpeg({ segments: [Buffer.from([0xff, 0xed, 0, 4, 65, 66])] }), limits)),
      jpegNoFrame: codeOf(() => inspectPhoto('image/jpeg', jpeg({ sofless: true }), limits)),
      jpegTruncated: codeOf(() => inspectPhoto('image/jpeg', jpeg().subarray(0, 12), limits)),
      mimeMismatch: codeOf(() => inspectPhoto('image/jpeg', webp(), limits)),
      png: codeOf(() => inspectPhoto('image/png', Buffer.from([0x89, 0x50, 0x4e, 0x47]), limits)),
      svg: codeOf(() => inspectPhoto('image/svg+xml', Buffer.from('<svg/>'), limits)),
      empty: codeOf(() => inspectPhoto('image/jpeg', Buffer.alloc(0), limits)),
      oversizeBytes: codeOf(() => inspectPhoto('image/jpeg', Buffer.alloc(limits.photoDecodedBytes + 1, 0xff), limits)),
      oversizeDimension: codeOf(() => inspectPhoto('image/jpeg', jpeg({ width: 2049 }), limits)),
      webpExif: codeOf(() => inspectPhoto('image/webp', webp({ extra: [['EXIF', Buffer.from('GPS')]] }), limits)),
      webpXmp: codeOf(() => inspectPhoto('image/webp', webp({ extra: [['XMP ', Buffer.from('xml')]] }), limits)),
      webpAnim: codeOf(() => inspectPhoto('image/webp', webp({ extra: [['ANIM', Buffer.alloc(6)]] }), limits)),
      webpOversize: codeOf(() => inspectPhoto('image/webp', webp({ width: 4000 }), limits)),
    };
    assert.deepEqual(cases, {
      jpegApp1AfterScan: 'PHOTO_METADATA_FORBIDDEN', jpegApp1BetweenScans: 'PHOTO_METADATA_FORBIDDEN', jpegApp1AfterSecondScan: 'PHOTO_METADATA_FORBIDDEN',
      jpegCommentAfterScan: 'PHOTO_METADATA_FORBIDDEN', jpegAllowedAppAfterScan: 'PHOTO_METADATA_FORBIDDEN', jpegApp0WrongId: 'PHOTO_METADATA_FORBIDDEN', jpegApp2Mpf: 'PHOTO_METADATA_FORBIDDEN',
      jpegTwoScansOk: 'accepted', jpegAfterEoi: 'PHOTO_MALFORMED', jpegSecondImageAfterEoi: 'PHOTO_MALFORMED', jpegNoEoi: 'PHOTO_MALFORMED', jpegUnknownMarker: 'PHOTO_MALFORMED', stuffedApp1BytesInScan: 'accepted',
      chromeCanvasJpeg: 'accepted', chromeCanvasWebp: 'accepted', webpUnknownChunk: 'PHOTO_METADATA_FORBIDDEN', webpTrailingGarbage: 'PHOTO_MALFORMED',
      okJpeg: 'accepted', okWebp: 'accepted', jpegExif: 'PHOTO_METADATA_FORBIDDEN', jpegComment: 'PHOTO_METADATA_FORBIDDEN', jpegApp13: 'PHOTO_METADATA_FORBIDDEN',
      jpegNoFrame: 'PHOTO_MALFORMED', jpegTruncated: 'PHOTO_MALFORMED', mimeMismatch: 'PHOTO_MALFORMED', png: 'PHOTO_TYPE_FORBIDDEN', svg: 'PHOTO_TYPE_FORBIDDEN',
      empty: 'PHOTO_MALFORMED', oversizeBytes: 'PHOTO_TOO_LARGE', oversizeDimension: 'PHOTO_DIMENSIONS_TOO_LARGE',
      webpExif: 'PHOTO_METADATA_FORBIDDEN', webpXmp: 'PHOTO_METADATA_FORBIDDEN', webpAnim: 'PHOTO_TYPE_FORBIDDEN', webpOversize: 'PHOTO_DIMENSIONS_TOO_LARGE',
    });
    return { cases, boundary: 'full marker walk on synthetic bytes and desktop Chrome 156 canvas output; pixels are not decoded and iOS/Android browsers were not run. Actual resize/EXIF removal belongs to phone-web and e2e.' };
  });

  scenario('consent-and-envelope-policy', { grade, expected: 'consent version and explicit dimension consent required; photo needs a separate opt-in; unknown fields, scene, file names and smuggled photoRef refused; default transfer has no photo' }, () => {
    const photoBytes = jpeg();
    const photo = { mime: 'image/jpeg', dataBase64: photoBytes.toString('base64') };
    const withRef = () => makeDraft(randomUUID(), { payload: { photoRef: `sha256:${sha256Hex(photoBytes)}` } });
    const cases: Record<string, string> = {
      dimensionsOnly: codeOf(() => submission(envelope())),
      withPhoto: codeOf(() => submission(envelope({ consent: PHOTO_CONSENT, draft: withRef(), photo }))),
      noConsent: codeOf(() => submission({ draft: makeDraft(randomUUID()) })),
      dimensionsFalse: codeOf(() => submission(envelope({ consent: { ...CONSENT, dimensionsConsent: false } }))),
      versionUnknown: codeOf(() => submission(envelope({ consent: { ...CONSENT, consentVersion: 'v0' } }))),
      consentExtra: codeOf(() => submission(envelope({ consent: { ...CONSENT, marketing: true } }))),
      photoWithoutOptIn: codeOf(() => submission(envelope({ draft: withRef(), photo }))),
      photoFileName: codeOf(() => submission(envelope({ consent: PHOTO_CONSENT, draft: withRef(), photo: { ...photo, fileName: 'IMG_1.jpg' } }))),
      photoBadBase64: codeOf(() => submission(envelope({ consent: PHOTO_CONSENT, draft: withRef(), photo: { ...photo, dataBase64: '@@@@' } }))),
      refWithoutPhoto: codeOf(() => submission(envelope({ draft: makeDraft(randomUUID(), { payload: { photoRef: 'IMG_1.jpg' } }) }))),
      refFileNameWithPhoto: codeOf(() => submission(envelope({ consent: PHOTO_CONSENT, draft: makeDraft(randomUUID(), { payload: { photoRef: 'https://public/x.jpg' } }), photo }))),
      unknownTop: codeOf(() => submission(envelope({ scene: {} }))),
      vehicleControl: codeOf(() => submission(envelope({ vehicleControl: { seat: 1 } }))),
      draftExtra: codeOf(() => submission(envelope({ draft: { ...makeDraft(randomUUID()), hack: 1 } }))),
      fullEnvelope: codeOf(() => submission(envelope({ draft: { item: {}, profile: {}, scene: {}, draft: {}, inbound: {} } }))),
      baseIncomplete: codeOf(() => submission(envelope({ draft: { ...makeDraft(randomUUID()), baseItemRevision: 2 } }))),
      jsonTooLarge: codeOf(() => submission(envelope(), limits.draftJsonBodyBytes + 1)),
      notObject: codeOf(() => submission(null)),
    };
    assert.deepEqual(cases, {
      dimensionsOnly: 'accepted', withPhoto: 'accepted', noConsent: 'CONSENT_REQUIRED', dimensionsFalse: 'CONSENT_REQUIRED', versionUnknown: 'CONSENT_VERSION_UNSUPPORTED',
      consentExtra: 'CONSENT_INVALID', photoWithoutOptIn: 'PHOTO_CONSENT_REQUIRED', photoFileName: 'PHOTO_FIELD_FORBIDDEN', photoBadBase64: 'PHOTO_MALFORMED',
      refWithoutPhoto: 'PHOTO_REF_INVALID', refFileNameWithPhoto: 'PHOTO_REF_INVALID', unknownTop: 'ENVELOPE_FIELD_FORBIDDEN', vehicleControl: 'ENVELOPE_FIELD_FORBIDDEN',
      draftExtra: 'DRAFT_SCHEMA_INVALID', fullEnvelope: 'DRAFT_SCHEMA_INVALID', baseIncomplete: 'DRAFT_BASE_INCOMPLETE', jsonTooLarge: 'BODY_TOO_LARGE', notObject: 'ENVELOPE_INVALID',
    });
    return { cases };
  });

  scenario('contract-decode-consumed', { grade, expected: 'ItemDraft goes through @cargo/contracts decodeDefinition; invalid contract data is refused without echoing client text' }, () => {
    const sentinel = `LEAK-${randomUUID()}`;
    const bad = makeDraft(randomUUID(), { payload: { name: '', measuredDimensionsMm: { x: 0, y: 1, z: 1 }, [sentinel]: 1 } });
    try {
      submission(envelope({ draft: bad }));
      assert.fail('accepted');
    } catch (cause) {
      assert.ok(cause instanceof RelayError);
      assert.equal(cause.code, 'DRAFT_SCHEMA_INVALID');
      assert.equal(JSON.stringify({ m: cause.message, d: cause.details }).includes(sentinel), false);
    }
    return { code: 'DRAFT_SCHEMA_INVALID', echoedClientText: false };
  });

  scenario('missing-contract-build-fails-loudly', { grade, expected: 'if @cargo/contracts cannot load, importing the validator fails with the build instruction rather than skipping validation' }, () => {
    const script = `import { registerHooks } from 'node:module';
      registerHooks({ resolve(spec, ctx, next) { if (spec === '@cargo/contracts') throw new Error('simulated missing build'); return next(spec, ctx); } });
      await import('./src/domain/submission.ts');`;
    const run = spawnSync(process.execPath, ['--input-type=module', '-e', script], { cwd: new URL('../..', import.meta.url).pathname, encoding: 'utf8' });
    assert.notEqual(run.status, 0);
    assert.match(run.stderr, /@cargo\/contracts is not built/);
    return { exit: run.status, message: '@cargo/contracts is not built: run npm run build --workspace=@cargo/contracts' };
  });

  scenario('ack-shape', { grade, expected: 'only typed ack shapes parse; conflict only from PENDING; relay does not range-compare revisions beyond contract format' }, () => {
    const hash = 'a'.repeat(64);
    const id = randomUUID();
    const cases: Record<string, string> = {
      accepted: codeOf(() => parseAck(acceptedAck(hash, id))),
      conflict: codeOf(() => parseAck(conflictAck(hash, id))),
      rejected: codeOf(() => parseAck(rejectedAck(hash))),
      conflictFromConflict: codeOf(() => parseAck({ ...conflictAck(hash, id), expectedState: 'CONFLICT_REPORTED' })),
      badResult: codeOf(() => parseAck({ ...acceptedAck(hash, id), result: 'SCENE_WRITE' })),
      badExpected: codeOf(() => parseAck({ ...acceptedAck(hash, id), expectedState: 'ACCEPTED' })),
      badReject: codeOf(() => parseAck(rejectedAck(hash, 'WHATEVER'))),
      badRevisionFloat: codeOf(() => parseAck({ ...acceptedAck(hash, id), acceptedRevision: 1.5 })),
      mixedFields: codeOf(() => parseAck({ ...acceptedAck(hash, id), rejectCode: 'USER_DISCARDED' })),
    };
    assert.deepEqual(cases, { accepted: 'accepted', conflict: 'accepted', rejected: 'accepted', conflictFromConflict: 'ACK_INVALID', badResult: 'ACK_INVALID', badExpected: 'ACK_INVALID', badReject: 'ACK_INVALID', badRevisionFloat: 'ACK_INVALID', mixedFields: 'ACK_INVALID' });
    return { cases };
  });

  scenario('postgres-absent-is-blocked-not-substituted', { grade, expected: 'when PostgreSQL cannot start, test:postgres exits nonzero with status blocked and a checkpoint naming the required action; no memory or SQLite substitute and no CE subcase passes' }, () => {
    const dir = mkdtempSync(resolve(tmpdir(), 'relay-blocked-'));
    const run = spawnSync(process.execPath, ['test/support/run-suite.ts', 'postgres'], {
      cwd: new URL('../..', import.meta.url).pathname,
      encoding: 'utf8',
      env: { ...process.env, RELAY_TEST_PG_BIN: resolve(dir, 'missing-bin'), RELAY_EVIDENCE_DIR: dir, RELAY_EVIDENCE_PARTIAL: '' },
    });
    const read = (name: string) => JSON.parse(readFileSync(resolve(dir, name), 'utf8'));
    const facts = { exit: run.status, suite: read('postgres.json').status, passedCases: read('postgres.json').summary.passed, ce18: read('CE-18-01.json').status, checkpoint: read('postgres-checkpoint.json').status };
    rmSync(dir, { recursive: true, force: true });
    assert.deepEqual(facts, { exit: 1, suite: 'blocked', passedCases: 0, ce18: 'blocked', checkpoint: 'blocked' });
    return facts;
  });

  scenario('tls-and-url-startup-rules', { grade, expected: 'cleartext refused without demo-local; demo-local refused on non-loopback; http URLs refused without demo-local; base URLs cannot hold query/fragment; test profile refused outside demo-local; off-host DB needs verify-full' }, () => {
    const baseEnv = {
      RELAY_DATABASE_URL: 'postgres://u:p@127.0.0.1:1/x',
      PHONE_WEB_BASE_URL: 'https://phone.invalid/', PHONE_RELAY_BASE_URL: 'https://relay.invalid/', VEHICLE_RELAY_BASE_URL: 'https://relay.invalid/',
    };
    const tls = { RELAY_TLS_CERT_FILE: 'c', RELAY_TLS_KEY_FILE: 'k' };
    const outcome = (env: Record<string, string>) => {
      try {
        readEnv(env);
        return 'allowed';
      } catch (cause) {
        return (cause as Error).message;
      }
    };
    const cases = {
      cleartextDefault: outcome(baseEnv),
      demoLoopback: outcome({ ...baseEnv, RELAY_DEMO_LOCAL: '1' }),
      demoLan: outcome({ ...baseEnv, RELAY_DEMO_LOCAL: '1', RELAY_BIND_HOST: '192.168.0.5' }),
      tlsLan: outcome({ ...baseEnv, ...tls, RELAY_BIND_HOST: '192.168.0.5' }),
      partialTls: outcome({ ...baseEnv, RELAY_TLS_CERT_FILE: 'c' }),
      httpWithTls: outcome({ ...baseEnv, ...tls, PHONE_WEB_BASE_URL: 'http://phone.invalid/' }),
      urlWithFragment: outcome({ ...baseEnv, ...tls, PHONE_WEB_BASE_URL: 'https://phone.invalid/#x' }),
      offHostDb: outcome({ ...baseEnv, ...tls, RELAY_DATABASE_URL: 'postgres://u:p@db.invalid/x' }),
      offHostDbVerified: outcome({ ...baseEnv, ...tls, RELAY_DATABASE_URL: 'postgres://u:p@db.invalid/x?sslmode=verify-full' }),
    };
    assert.match(cases.cleartextDefault, /refusing cleartext/);
    assert.equal(cases.demoLoopback, 'allowed');
    assert.match(cases.demoLan, /refusing cleartext/);
    assert.equal(cases.tlsLan, 'allowed');
    assert.match(cases.partialTls, /set together/);
    assert.match(cases.httpWithTls, /must use https/);
    assert.match(cases.urlWithFragment, /must not carry/);
    assert.match(cases.offHostDb, /verify-full/);
    assert.equal(cases.offHostDbVerified, 'allowed');
    const badUrl = outcome({ ...baseEnv, ...tls, RELAY_DATABASE_URL: 'postgres://user:SECRETPW@bad url' });
    assert.equal(badUrl.includes('SECRETPW'), false);
    assert.match(badUrl, /not a valid URL/);
    const prod = readEnv({ ...baseEnv, ...tls });
    const short = loadConfig(new URL('../fixtures/config-short-ttl.json', import.meta.url).pathname);
    assert.throws(() => assertProfileAllowed(short, prod), /requires RELAY_DEMO_LOCAL/);
    return { cases, testProfileWithoutDemoLocal: 'refused' };
  });

  scenario('logger-allowlist', { grade, expected: 'the logger drops any field not on its allowlist, in the actual output' }, () => {
    const script = `import { log } from './src/logger.ts'; log('x', { requestId: 'r', authorization: 'SECRET', url: 'SECRET', body: 'SECRET', name: 'SECRET', status: 200 });`;
    const run = spawnSync(process.execPath, ['--input-type=module', '-e', script], { cwd: new URL('../..', import.meta.url).pathname, encoding: 'utf8' });
    assert.equal(run.status, 0);
    assert.equal(run.stdout.includes('SECRET'), false);
    const line = JSON.parse(run.stdout);
    assert.deepEqual(Object.keys(line).sort(), ['event', 'requestId', 'status', 'ts']);
    return { fields: Object.keys(line).sort() };
  });

  scenario('rate-limiter-bucket', { grade, expected: 'token bucket blocks at capacity, refills by time, and reports a positive wait' }, () => {
    const limiter = new RateLimiter();
    const rule = { capacity: 2, refillPerMinute: 60 };
    const t = 1_000_000;
    const seq = [limiter.take('k', rule, t), limiter.take('k', rule, t), limiter.take('k', rule, t), limiter.take('k', rule, t + 1000)];
    assert.deepEqual(seq, [0, 0, 1, 0]);
    return { sequence: seq };
  });

  scenario('route-inventory-namespaces', { grade, expected: 'all routes are under /vehicle/v1 or /phone/v1; no route takes a token in the path or query; no photo route under the phone namespace' }, () => {
    const templates = routes.map((route) => route.template);
    assert.ok(templates.every((t) => t.startsWith('/vehicle/v1/') || t.startsWith('/phone/v1/')));
    assert.ok(routes.every((route) => !route.allowedQuery.some((q) => /token|auth/i.test(q)) && !/token/i.test(route.template)));
    assert.equal(templates.filter((t) => t.startsWith('/phone/') && t.includes('photo')).length, 0);
    return { routes: routes.map((route) => `${route.method} ${route.template}`) };
  });
}
