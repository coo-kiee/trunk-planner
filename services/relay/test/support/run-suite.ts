// Runs one suite in a child and writes machine evidence. A suite that cannot start PostgreSQL exits nonzero and says blocked.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { relayRoot } from '../../src/config.ts';
import { TOKEN_IN_TEXT } from '../../src/domain/tokens.ts';
import type { CaseRecord } from './recorder.ts';

const suite = process.argv[2] === 'protocol' ? 'protocol' : 'postgres';
const projectRoot = resolve(relayRoot, '../..');
const evidenceDir = process.env.RELAY_EVIDENCE_DIR ?? resolve(projectRoot, 'evidence/product/relay');
mkdirSync(evidenceDir, { recursive: true });
const partial = resolve(relayRoot, `.local-pg/${suite}-partial-${process.pid}.jsonl`);
mkdirSync(resolve(relayRoot, '.local-pg'), { recursive: true });
rmSync(partial, { force: true });

/** Evidence travels with the delivery: no absolute local paths, and never anything shaped like a credential. */
function writeEvidence(name: string, value: unknown): void {
  const text = `${JSON.stringify(value, null, 2)}\n`.replaceAll(projectRoot, '<project>');
  if (TOKEN_IN_TEXT.test(text)) throw new Error(`refusing to write ${name}: credential-shaped text in evidence`);
  writeFileSync(resolve(evidenceDir, name), text);
}

const sha256File = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');

function treeDigest(paths: string[]): { digest: string; files: number } {
  const hash = createHash('sha256');
  let files = 0;
  const visit = (path: string) => {
    if (statSync(path).isDirectory()) {
      for (const name of readdirSync(path).sort()) visit(resolve(path, name));
    } else {
      hash.update(path.slice(relayRoot.length)).update(readFileSync(path));
      files += 1;
    }
  };
  paths.forEach(visit);
  return { digest: hash.digest('hex'), files };
}

const argv = ['node', '--test', '--test-concurrency=1', '--test-reporter=spec', `test/${suite}.test.ts`];
const started = new Date();
const run = spawnSync(process.execPath, argv.slice(1), {
  cwd: relayRoot,
  encoding: 'utf8',
  env: { ...process.env, RELAY_EVIDENCE_PARTIAL: partial },
  timeout: 600_000,
  maxBuffer: 64 * 1024 * 1024,
});
process.stdout.write(run.stdout);
process.stderr.write(run.stderr);
const exit = run.status ?? 1;

const lines = existsSync(partial) ? readFileSync(partial, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line)) : [];
const cases: CaseRecord[] = lines.filter((line) => line.id);
const environment = Object.assign({}, ...lines.filter((line) => line.environment).map((line) => line.environment));
const lock = JSON.parse(readFileSync(resolve(relayRoot, 'package-lock.json'), 'utf8'));
const pinned = (name: string) => ({ version: lock.packages[`node_modules/${name}`]?.version, integrity: lock.packages[`node_modules/${name}`]?.integrity, license: lock.packages[`node_modules/${name}`]?.license });

const started_cluster = Boolean(environment.postgres_select_version);
const allPassed = exit === 0 && cases.length > 0 && cases.every((c) => c.passed);
const status = allPassed ? 'passed' : started_cluster ? 'failed' : 'blocked';
const inputs = {
  relay_source: treeDigest(['src', 'migrations', 'config', 'test'].map((dir) => resolve(relayRoot, dir))),
  package_lock_sha256: sha256File(resolve(relayRoot, 'package-lock.json')),
  contracts_configuration_v1_sha256: sha256File(resolve(projectRoot, 'packages/contracts/configuration-v1.json')),
  contracts_schema_sha256: sha256File(resolve(projectRoot, 'packages/contracts/schema.json')),
};
const common = {
  schema: 'relay-evidence-v1',
  suite,
  status,
  argv: ['npm', '--prefix', 'services/relay', 'run', `test:${suite}`],
  inner_argv: argv,
  cwd: 'services/relay',
  exit,
  started_at: started.toISOString(),
  finished_at: new Date().toISOString(),
  inputs,
  environment: {
    node: process.version,
    platform: `${process.platform}-${process.arch}`,
    embedded_postgres: pinned('embedded-postgres'),
    embedded_postgres_binary: pinned(`@embedded-postgres/${process.platform}-${process.arch}`),
    pg_driver: pinned('pg'),
    canonicalize: pinned('canonicalize'),
    contracts_resolved_relative: createRequire(resolve(relayRoot, 'src/x.js')).resolve('@cargo/contracts').slice(projectRoot.length + 1),
    ...environment,
  },
  not_run: {
    public_https_and_hosting: 'not_run: not approved. local self-signed TLS is not public HTTPS.',
    pg_dump_restore: 'not_run: the embedded distribution ships initdb/pg_ctl/postgres only.',
    at_rest_encryption: 'not_run: host volume and backup encryption not observed; plaintext local data directory protected by OS permissions.',
    phone_and_vehicle_clients: 'not_run here: phone-web, vehicle clients and e2e own the UI and combined flows.',
    real_vehicle: 'not_run: no real vehicle test.',
  },
};
writeEvidence(`${suite}.json`, { ...common, summary: { total: cases.length, passed: cases.filter((c) => c.passed).length, failed: cases.filter((c) => !c.passed).length }, cases });

/** A subcase passes only when its full suite passed and it has at least one matching case. */
function writeCe(id: string, ce: string, responsibility: string, boundary: string): void {
  const matching = cases.filter((c) => c.ce.includes(ce));
  const result = {
    ...common,
    schema: 'relay-ce-evidence-v1',
    id,
    owner: 'relay',
    responsibility,
    status: allPassed && matching.length > 0 ? 'passed' : status === 'blocked' ? 'blocked' : 'failed',
    grade: 'real_postgres_local_integration',
    expected_actual: matching.map(({ id: caseId, expected, actual, passed, grade }) => ({ case: caseId, grade, expected, actual, passed })),
    boundary,
  };
  writeEvidence(`${id}.json`, result);
}

if (suite === 'postgres') {
  writeCe('CE-18-01', 'CE-18', 'PostgreSQL unique/hash/expiry/revoke로 중복·재생 프로토콜 거부와 재시작복구 시험', 'relay protocol only; vehicle Room application and phone outbox behaviour remain CE-18-02/03.');
  writeCe('CE-19-01', 'CE-19', '차량이 반환한 conflict/acceptedRevision 전달·보존 및 draft상태 CAS', 'the relay transports the vehicle decision; it does not compare item revisions. User choice UI is not claimed.');
  writeCe('CE-20-01', 'CE-20', '의도적 종료/재시작 시 자체 미수락draft·ack 복구/TTL·프로토콜 반례', 'relay and its database only; the vehicle-alone flow and phone waiting display are not claimed.');
}

// CE-22-05 restates whichever suites have produced evidence so grades cannot be read as stronger than they are.
const readSuite = (name: string) => {
  const path = resolve(evidenceDir, `${name}.json`);
  return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : null;
};
const suites = { postgres: readSuite('postgres'), protocol: readSuite('protocol') };
const gradeCounts = (data: { cases?: CaseRecord[] } | null) => (data?.cases ?? []).reduce<Record<string, number>>((acc, c) => ({ ...acc, [c.grade]: (acc[c.grade] ?? 0) + 1 }), {});
writeEvidence('CE-22-05.json', {
  schema: 'relay-ce-evidence-v1',
  id: 'CE-22-05',
  owner: 'relay',
  responsibility: '자기 실제PostgreSQL/protocol 결과등급 명시',
  status: suites.postgres?.status === 'passed' && suites.protocol?.status === 'passed' ? 'passed' : 'incomplete',
  argv: common.argv,
  grades: {
    real_postgres_local_integration: 'real PostgreSQL + real relay process + real HTTP on loopback; not a public deployment',
    local_tls_real_postgres: 'the above behind a self-signed local certificate; not public HTTPS',
    unit: 'pure functions without a database; never counted as PostgreSQL proof',
    fixture: 'not used as a result grade; test items and JPEG/WebP headers are synthetic and are only inputs',
    emulator: 'not_run here',
    real_vehicle: 'not_run: no real vehicle test',
  },
  suites: Object.fromEntries(Object.entries(suites).map(([name, data]) => [name, data && { status: data.status, exit: data.exit, finished_at: data.finished_at, summary: data.summary, grade_counts: gradeCounts(data) }])),
});

if (!started_cluster && suite === 'postgres') {
  const checkpoint = {
    status: 'blocked',
    reason: 'isolated PostgreSQL did not start; no memory or SQLite substitute is used',
    exit,
    required_action: 'npm --prefix services/relay install --no-workspaces (exact-pinned embedded-postgres). System-wide, brew and Docker installs were not attempted.',
    resume_argv: common.argv,
  };
  writeEvidence('postgres-checkpoint.json', checkpoint);
}
if (started_cluster && suite === 'postgres') rmSync(resolve(evidenceDir, 'postgres-checkpoint.json'), { force: true });
rmSync(partial, { force: true });
process.exit(status === 'passed' ? 0 : 1);
