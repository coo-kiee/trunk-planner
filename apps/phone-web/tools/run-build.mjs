// Clean production build with a machine-readable record. Writes the evidence and exits non-zero on any failure.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { appRoot, evidenceDir, repoRoot, writeEvidence } from './lib/evidence.mjs';

const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const run = (argv) => {
  const started = new Date().toISOString();
  const result = spawnSync(argv[0], argv.slice(1), { cwd: appRoot, encoding: 'utf8', env: { ...process.env, NEXT_TELEMETRY_DISABLED: '1' } });
  return { argv, cwd: 'apps/phone-web', exit: result.status, started_at: started, finished_at: new Date().toISOString(), stdout_tail: result.stdout?.slice(-1500), stderr_tail: result.stderr?.slice(-1500) };
};

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const contractsDist = join(repoRoot, 'packages/contracts/dist/ts/index.d.ts');
const contractsPresent = existsSync(contractsDist);
rmSync(join(appRoot, '.next'), { recursive: true, force: true });

const steps = [];
if (contractsPresent) {
  for (const argv of [['node', 'tools/generate-validator.mjs'], ['npx', 'next', 'typegen'], ['npx', 'tsc', '--noEmit'], ['npx', 'next', 'build']]) {
    const step = run(argv);
    steps.push(step);
    if (step.exit !== 0) break;
  }
}
const passed = contractsPresent && steps.length === 4 && steps.every((step) => step.exit === 0);

let bundle = null;
const chunks = join(appRoot, '.next/static');
if (passed && existsSync(chunks)) {
  const files = walk(chunks);
  const scripts = files.filter((file) => file.endsWith('.js'));
  const text = scripts.map((file) => readFileSync(file, 'utf8')).join('\n');
  const overall = createHash('sha256');
  for (const file of [...files].sort()) overall.update(relative(chunks, file)).update(readFileSync(file));
  bundle = {
    static_file_count: files.length,
    static_total_bytes: files.reduce((sum, file) => sum + statSync(file).size, 0),
    static_tree_sha256: overall.digest('hex'),
    build_id: readFileSync(join(appRoot, '.next/BUILD_ID'), 'utf8').trim(),
    // Enforced facts about what shipped, not just about the source.
    service_worker_registration_in_bundle: /serviceWorker\s*\.\s*register/.test(text),
    ajv_runtime_compile_in_bundle: /new Function\(/.test(text) && /ajv/i.test(text),
  };
}

const record = {
  schema: 'phone-web-build-v1',
  status: passed ? 'passed' : 'failed',
  grade: 'local_clean_build',
  argv: ['npm', '--prefix', 'apps/phone-web', 'run', 'build'],
  steps,
  environment: { node: process.version, platform: `${process.platform}-${process.arch}`, next: JSON.parse(readFileSync(join(appRoot, 'node_modules/next/package.json'), 'utf8')).version },
  install_reproduction: { argv: ['npm', '--prefix', 'apps/phone-web', 'ci', '--no-workspaces'], lock_sha256: sha256(join(appRoot, 'package-lock.json')) },
  inputs: {
    contracts_dist_present: contractsPresent,
    contracts_dist_index_sha256: contractsPresent ? sha256(contractsDist) : null,
    schema_sha256: sha256(join(repoRoot, 'packages/contracts/schema.json')),
    tokens_css_sha256: sha256(join(repoRoot, 'packages/design-tokens/tokens.css')),
  },
  bundle,
  evidence_dir: relative(repoRoot, evidenceDir),
};
writeEvidence('build.json', record);
if (!contractsPresent) console.error('packages/contracts is not built: run `npm run build --workspace=@cargo/contracts` at the repository root');
console.log(JSON.stringify({ status: record.status, steps: steps.map((step) => [step.argv.join(' '), step.exit]), bundle }));
if (!passed) {
  for (const step of steps.filter((item) => item.exit !== 0)) console.error(step.stdout_tail, step.stderr_tail);
  process.exit(1);
}
