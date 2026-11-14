import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
import { get as getHttp } from 'node:http';
import { get, request } from 'node:https';
import type { TLSSocket } from 'node:tls';
import { resolve } from 'node:path';
import { scenario } from '../support/recorder.ts';
import { migrate, startRelay } from '../support/relay-process.ts';
import { DB, env } from '../support/suite-env.ts';

const HTTPS_URLS = { PHONE_WEB_BASE_URL: 'https://phone.invalid/', PHONE_RELAY_BASE_URL: 'https://relay.invalid/', VEHICLE_RELAY_BASE_URL: 'https://relay.invalid/' };

export function tlsScenarios(): void {
  scenario('local-tls-non-demo-listener', { grade: 'local_tls_real_postgres', expected: 'without demo-local: production profile serves only with TLS and https URLs, sends HSTS, issues https QR URLs; untrusted clients and plain HTTP get no API answer. Self-signed loopback only; not public HTTPS.' }, async () => {
    const certFile = resolve(env.cluster.dir, 'tls-cert.pem');
    const keyFile = resolve(env.cluster.dir, 'tls-key.pem');
    const openssl = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', keyFile, '-out', certFile, '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1'], { encoding: 'utf8' });
    assert.equal(openssl.status, 0, openssl.stderr);
    assert.equal(migrate(env.cluster, DB).status, 0);

    const withHttpUrls = await startRelay({
      databaseUrl: env.cluster.runtimeUrl(DB),
      scheme: 'https',
      extraEnv: { RELAY_DEMO_LOCAL: '', RELAY_TLS_CERT_FILE: certFile, RELAY_TLS_KEY_FILE: keyFile },
    }).then(() => 'started', (cause: Error) => /Error: (.*must use https.*)/.exec(cause.message)?.[1] ?? 'other failure');
    assert.match(withHttpUrls, /must use https/);

    const relay = await startRelay({
      databaseUrl: env.cluster.runtimeUrl(DB),
      scheme: 'https',
      extraEnv: { RELAY_DEMO_LOCAL: '', RELAY_TLS_CERT_FILE: certFile, RELAY_TLS_KEY_FILE: keyFile, ...HTTPS_URLS },
    });
    env.extra.push(relay);
    const ca = readFileSync(certFile);
    const health = await new Promise<{ status?: number; hsts?: string; protocol: string | null }>((done, fail) => {
      get(`${relay.base}/healthz`, { ca }, (res) => {
        const protocol = (res.socket as TLSSocket).getProtocol();
        res.resume();
        res.on('end', () => done({ status: res.statusCode, hsts: res.headers['strict-transport-security'], protocol }));
      }).on('error', fail);
    });
    const qr = await new Promise<string>((done, fail) => {
      const req = request(`${relay.base}/vehicle/v1/pairings`, { method: 'POST', ca }, (res) => {
        let body = '';
        res.on('data', (chunk) => { body += chunk; });
        res.on('end', () => done(new URL(JSON.parse(body).qrUrl).protocol));
      });
      req.on('error', fail);
      req.end();
    });
    const untrusted = await new Promise<string>((done) => {
      get(`${relay.base}/healthz`, (res) => { res.resume(); done(`status:${res.statusCode}`); }).on('error', (cause: NodeJS.ErrnoException) => done(cause.code ?? 'error'));
    });
    const plain = await new Promise<string>((done) => {
      const req = getHttp(`http://127.0.0.1:${relay.port}/healthz`, (res) => { res.resume(); done(`status:${res.statusCode}`); });
      req.on('error', (cause: NodeJS.ErrnoException) => done(cause.code ?? 'error'));
      req.setTimeout(3000, () => { req.destroy(); done('timeout'); });
    });
    await relay.stop();
    rmSync(keyFile, { force: true });
    assert.equal(health.status, 200);
    assert.ok(String(health.hsts).includes('max-age'));
    assert.equal(qr, 'https:');
    assert.equal(untrusted, 'DEPTH_ZERO_SELF_SIGNED_CERT');
    assert.ok(!plain.startsWith('status:2'));
    return { health, qrScheme: qr, refusedHttpUrls: withHttpUrls, untrustedClient: untrusted, plainHttpToTlsPort: plain, boundary: 'self-signed loopback; public HTTPS not_run' };
  });
}
