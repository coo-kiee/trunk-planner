// Starts its own Next server and, when asked, its own PostgreSQL + real relay. It signals and deletes only what it started.
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { join } from 'node:path';
import { appRoot } from './evidence.mjs';

const RESERVED = new Set([3100, 3101, 23000, 23120, 5432, 5554, 5555]);

export async function freePort() {
  for (;;) {
    const port = await new Promise((resolve, reject) => {
      const probe = createServer();
      probe.once('error', reject);
      probe.listen(0, '127.0.0.1', () => {
        const { port: chosen } = probe.address();
        probe.close(() => resolve(chosen));
      });
    });
    if (!RESERVED.has(port)) return port;
  }
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function startNext({ port, relayUrl }) {
  const output = [];
  const env = { ...process.env, NEXT_TELEMETRY_DISABLED: '1', PHONE_RELAY_BASE_URL: relayUrl ?? '' };
  const child = spawn(join(appRoot, 'node_modules/.bin/next'), ['start', '-p', String(port), '-H', '127.0.0.1'], { cwd: appRoot, env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', (chunk) => output.push(chunk.toString()));
  child.stderr.on('data', (chunk) => output.push(chunk.toString()));
  const exited = new Promise((resolve) => child.once('exit', resolve));
  const origin = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 30_000;
  for (;;) {
    const up = await fetch(`${origin}/privacy`).then((response) => response.ok).catch(() => false);
    if (up) break;
    if (Date.now() > deadline || child.exitCode !== null) throw new Error(`next start did not come up: ${output.join('').slice(-800)}`);
    await sleep(150);
  }
  return { origin, port, output, stop: async () => { child.kill('SIGTERM'); await exited; } };
}

/**
 * The relay's own harness, unmodified. configFile picks a shorter-TTL or relaxed-rate test configuration (relay's test fixtures).
 * A restart keeps the same port and database: only the process goes down, which is what CE-20 needs.
 */
export async function startRelayStack({ label, phoneOrigin, configFile, relayPort, tls, relayPublicUrl }) {
  const harness = await import('../../../../services/relay/test/support/pg-harness.ts');
  const relayProcess = await import('../../../../services/relay/test/support/relay-process.ts');
  const cluster = await harness.createCluster(label);
  const database = 'cargo_relay_phone_e2e';
  await cluster.createDatabase(database);
  const migrated = relayProcess.migrate(cluster, database, configFile);
  if (migrated.status !== 0) throw new Error(`migrate failed: ${migrated.stderr}`);
  const port = relayPort ?? (await freePort());
  const relay = await relayProcess.startRelay({
    databaseUrl: cluster.runtimeUrl(database),
    configFile,
    extraEnv: {
      PHONE_WEB_BASE_URL: `${phoneOrigin}/`,
      PHONE_RELAY_BASE_URL: relayPublicUrl ?? `http://127.0.0.1:${port}/`,
      VEHICLE_RELAY_BASE_URL: `http://10.0.2.2:${port}/`,
      RELAY_PORT: String(port),
      ...(tls ? { RELAY_TLS_CERT_FILE: tls.certFile, RELAY_TLS_KEY_FILE: tls.keyFile } : {}),
    },
    scheme: tls ? 'https' : 'http',
  });
  return {
    relay,
    port,
    base: `http://127.0.0.1:${port}`,
    output: relayProcess.capturedRelayOutput,
    postgresVersion: cluster.version,
    stopAll: async () => {
      await relay.stop();
      await cluster.stop();
      cluster.destroy();
    },
  };
}
