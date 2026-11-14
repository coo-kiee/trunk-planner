// npm --prefix apps/phone-web run test:e2e
// Real system Chrome, new profile, free ports, real relay and new PostgreSQL. The vehicle is a protocol fixture, and evidence says so.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { launchChrome, chromePath, VIEWPORTS } from '../lib/browser.mjs';
import { appRoot, evidenceDir, repoRoot, writeEvidence } from '../lib/evidence.mjs';
import { freePort, startNext, startRelayStack } from '../lib/stack.mjs';
import { ensureCertificate, startTlsProxy } from '../local-tls.mjs';
import { runCe18 } from './ce18.mjs';
import { runCe19 } from './ce19.mjs';
import { runCe20 } from './ce20.mjs';
import { NOT_TESTED, runCe22 } from './ce22.mjs';
import { SCREEN_DIR } from './flows.mjs';
import { runIntegrity } from './integrity.mjs';
import { runJourneys } from './journey.mjs';
import { Recorder } from './recorder.mjs';
import { runSecurity } from './security.mjs';

const ARGV = ['npm', '--prefix', 'apps/phone-web', 'run', 'test:e2e'];
const started = new Date().toISOString();
const sha256File = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const only = process.env.E2E_ONLY?.split(',');
const rec = new Recorder();
const cleanups = [];
const screenshots = { journey: [], states: [] };

async function cleanup() {
  for (const task of cleanups.reverse()) await task().catch((cause) => process.stderr.write(`cleanup: ${cause}\n`));
}

async function main() {
  if (!existsSync(join(appRoot, '.next/BUILD_ID'))) throw new Error('no production build: run `npm --prefix apps/phone-web run build` first');
  if (!only) rmSync(SCREEN_DIR, { recursive: true, force: true });
  mkdirSync(SCREEN_DIR, { recursive: true });

  // Main stack: real TTLs, relaxed rate limits so that many short test sessions fit in a minute.
  const nextPort = await freePort();
  const stack = await startRelayStack({ label: 'phone-main', phoneOrigin: `http://127.0.0.1:${nextPort}`, configFile: 'test/fixtures/config-load.json' });
  cleanups.push(stack.stopAll);
  const next = await startNext({ port: nextPort, relayUrl: stack.base });
  cleanups.push(next.stop);

  // Short real TTLs, for the expiry boundary.
  const shortNextPort = await freePort();
  const shortStack = await startRelayStack({ label: 'phone-short', phoneOrigin: `http://127.0.0.1:${shortNextPort}`, configFile: 'test/fixtures/config-short-ttl.json' });
  cleanups.push(shortStack.stopAll);
  const shortNext = await startNext({ port: shortNextPort, relayUrl: shortStack.base });
  cleanups.push(shortNext.stop);

  // A misconfigured deployment: cleartext relay on a non-loopback address.
  const badNext = await startNext({ port: await freePort(), relayUrl: 'http://192.168.0.5:43002' });
  cleanups.push(badNext.stop);

  // TLS: a self-signed certificate and hostnames mapped only inside this browser, so the app's real secure-context path runs.
  const certificate = ensureCertificate(['phone.cargo.test', 'relay.cargo.test', 'localhost', '127.0.0.1']);
  const tlsPort = await freePort();
  const tlsRelayPort = await freePort();
  const tlsOrigin = `https://phone.cargo.test:${tlsPort}`;
  const tlsRelay = await startRelayStack({ label: 'phone-tls', phoneOrigin: tlsOrigin, configFile: 'test/fixtures/config-load.json', relayPort: tlsRelayPort, tls: certificate, relayPublicUrl: `https://relay.cargo.test:${tlsRelayPort}/` });
  cleanups.push(tlsRelay.stopAll);
  const tlsNext = await startNext({ port: await freePort(), relayUrl: `https://relay.cargo.test:${tlsRelayPort}` });
  cleanups.push(tlsNext.stop);
  const proxy = await startTlsProxy({ ...certificate, targetPort: tlsNext.port, port: tlsPort });
  cleanups.push(proxy.stop);

  const browser = await launchChrome(['--host-resolver-rules=MAP cargo-insecure.test 127.0.0.1']);
  cleanups.push(() => browser.close());
  const tlsBrowser = await launchChrome(['--host-resolver-rules=MAP phone.cargo.test 127.0.0.1, MAP relay.cargo.test 127.0.0.1', `--ignore-certificate-errors-spki-list=${certificate.spkiSha256Base64}`]);
  cleanups.push(() => tlsBrowser.close());

  // Node's fetch in the vehicle fixture talks to the TLS relay directly; the certificate is trusted only inside this process.
  process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
  const env = {
    browser, tlsBrowser, stack, next, shortStack, shortNext, badNext,
    tlsStack: { directBase: `https://127.0.0.1:${tlsRelayPort}`, fingerprint256: certificate.fingerprint256 },
  };

  const wanted = (name) => !only || only.includes(name);
  if (wanted('journey')) screenshots.journey = await runJourneys(rec, env);
  if (wanted('security')) screenshots.states.push(...await runSecurity(rec, env));
  if (wanted('integrity')) screenshots.states.push(...await runIntegrity(rec, env));
  if (wanted('ce18')) screenshots.states.push(...await runCe18(rec, env));
  if (wanted('ce19')) screenshots.states.push(...await runCe19(rec, env));
  if (wanted('ce20')) screenshots.states.push(...await runCe20(rec, env));
  if (wanted('ce22')) await runCe22(rec, env);
}

const SUBCASES = [
  ['CE-18-03', '만료/해제·중복 ack·재전송 outbox 화면과 상태 시험', 'CE-18-03.json'],
  ['CE-19-04', '양쪽수정 충돌·대기 outbox·차량확정revision 구분 표시와 선택 후 ack 처리', 'CE-19-04.json'],
  ['CE-20-04', '릴레이 down 시 전송함 대기·전경재시도 및 로컬draft 복원', 'CE-20-04.json'],
  ['CE-22-04', '자기 브라우저 viewport와 real-phone 스캔 분리', 'CE-22-04.json'],
];

function inputs() {
  const hash = (path) => ({ path, sha256: sha256File(join(repoRoot, path)) });
  return [hash('packages/contracts/schema.json'), hash('packages/design-tokens/tokens.css'), hash('services/relay/docs/protocol-v1.md'), hash('services/relay/config/relay-config-v1.json'), hash('apps/phone-web/package-lock.json')];
}

let fatal = null;
try {
  await main();
} catch (cause) {
  fatal = String(cause?.stack ?? cause).slice(0, 1800);
  process.stderr.write(`${fatal}\n`);
}
await cleanup();

const chromeVersion = spawnSync(chromePath(), ['--version'], { encoding: 'utf8' }).stdout?.trim();
const environment = { node: process.version, platform: `${process.platform}-${process.arch}`, browser: chromeVersion, profile: 'new temporary profile per run', relay_config: 'services/relay/test/fixtures/config-load.json (실제 TTL, 완화된 속도 제한); 만료 시험만 config-short-ttl.json' };
const common = { argv: ARGV, cwd: 'project root', started_at: started, finished_at: new Date().toISOString(), environment, inputs: inputs() };
const passed = !fatal && rec.allPassed;

if (!only) {
  for (const [id, responsibility, file] of SUBCASES) {
    const scenarios = rec.select(id);
    writeEvidence(file, {
      schema: 'phone-web-ce-evidence-v1', id, owner: 'phone-web', responsibility,
      status: scenarios.length > 0 && scenarios.every((scenario) => scenario.status === 'passed') ? 'passed' : 'failed',
      exit: passed ? 0 : 1, ...common, scenarios,
      grades: [...new Set(scenarios.map((scenario) => scenario.grade))],
      boundary: '차량 역할은 relay protocol v1을 그대로 호출하는 fixture다. 실제 차량 앱·Room·실폰·실차 결과가 아니다. 결합은 후속 e2e 담당.',
      not_tested: id === 'CE-22-04' ? NOT_TESTED : undefined,
    });
  }
  writeEvidence('browser.json', {
    schema: 'phone-web-browser-v1',
    status: passed ? 'passed' : 'failed',
    exit: passed ? 0 : 1,
    fatal,
    ...common,
    viewports: VIEWPORTS,
    summary: { scenarios: rec.scenarios.length, passed: rec.scenarios.filter((scenario) => scenario.status === 'passed').length, checks: rec.scenarios.reduce((sum, scenario) => sum + scenario.checks.length, 0) },
    scenarios: rec.scenarios,
    screenshots,
    not_tested: NOT_TESTED,
  });
}
process.stdout.write(`${JSON.stringify({ status: passed ? 'passed' : 'failed', scenarios: rec.scenarios.length, failed: rec.scenarios.filter((scenario) => scenario.status !== 'passed').map((scenario) => scenario.name), fatal: fatal?.slice(0, 300) })}\n`);
process.exit(passed ? 0 : 1);
