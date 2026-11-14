// Local HTTPS in front of `next start`, for trying the app on a real phone on the same network or in an isolated browser.
// The certificate lives in the git-ignored .local-tls/. Nothing is installed into any trust store: that stays a manual, explicit step.
//   node tools/local-tls.mjs --target-port 3210 --port 3443 [--lan-host 192.168.0.5]
// Without --lan-host it listens on loopback only.
import { spawnSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { isIP } from 'node:net';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { appRoot } from './lib/evidence.mjs';

/** Creates a fresh self-signed certificate for these hosts unless one already covers them. */
export function ensureCertificate(hosts, directory = join(appRoot, '.local-tls')) {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const certFile = join(directory, 'cert.pem');
  const keyFile = join(directory, 'key.pem');
  const wanted = hosts.map((host) => (isIP(host) ? `IP Address:${host}` : `DNS:${host}`));
  const covered = existsSync(certFile) && wanted.every((name) => (new X509Certificate(readFileSync(certFile)).subjectAltName ?? '').split(', ').includes(name));
  if (!covered) {
    const san = hosts.map((host) => (isIP(host) ? `IP:${host}` : `DNS:${host}`)).join(',');
    const result = spawnSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', keyFile, '-out', certFile, '-days', '30', '-subj', '/CN=cargo-phone-local', '-addext', `subjectAltName=${san}`], { encoding: 'utf8' });
    if (result.status !== 0) throw new Error(`openssl failed: ${result.stderr}`);
  }
  const certificate = new X509Certificate(readFileSync(certFile));
  const spkiDer = certificate.publicKey.export({ type: 'spki', format: 'der' });
  return { certFile, keyFile, spkiSha256Base64: createHash('sha256').update(spkiDer).digest('base64'), fingerprint256: certificate.fingerprint256 };
}

export function startTlsProxy({ certFile, keyFile, targetPort, port, host = '127.0.0.1' }) {
  const server = createHttpsServer({ cert: readFileSync(certFile), key: readFileSync(keyFile) }, (req, res) => {
    const upstream = httpRequest({ host: '127.0.0.1', port: targetPort, method: req.method, path: req.url, headers: req.headers }, (response) => {
      res.writeHead(response.statusCode ?? 502, response.headers);
      response.pipe(res);
    });
    upstream.on('error', () => {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('next start에 연결할 수 없습니다.');
    });
    req.pipe(upstream);
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve({ server, port: server.address().port, stop: () => new Promise((done) => { server.closeAllConnections(); server.close(() => done()); }) }));
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const option = (name, fallback) => {
    const index = process.argv.indexOf(`--${name}`);
    return index === -1 ? fallback : process.argv[index + 1];
  };
  const lanHost = option('lan-host');
  const hosts = ['localhost', '127.0.0.1', ...(lanHost ? [lanHost] : [])];
  const certificate = ensureCertificate(hosts);
  const proxy = await startTlsProxy({ ...certificate, targetPort: Number(option('target-port', '3210')), port: Number(option('port', '3443')), host: lanHost ?? '127.0.0.1' });
  writeFileSync(join(appRoot, '.local-tls/last-run.json'), JSON.stringify({ port: proxy.port, hosts }));
  console.log(JSON.stringify({ event: 'tls_proxy_ready', port: proxy.port, hosts, fingerprint256: certificate.fingerprint256, trust: 'manual: not installed anywhere' }));
}
