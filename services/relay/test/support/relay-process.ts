// Starts the actual relay entrypoint as a child process, so restart and log assertions exercise the real thing.
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { resolve } from 'node:path';
import { relayRoot } from '../../src/config.ts';
import { sleep, type PgCluster } from './pg-harness.ts';

/** Every byte the relay ever wrote in this suite, scanned at the end for secrets. */
export const capturedRelayOutput: string[] = [];

export function migrate(cluster: PgCluster, database: string, configFile?: string, extraArgs: string[] = []) {
  const result = spawnSync(process.execPath, ['src/bin/migrate.ts', ...extraArgs], {
    cwd: relayRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      RELAY_MIGRATION_DATABASE_URL: cluster.ownerUrl(database),
      RELAY_RUNTIME_ROLE: 'relay_app',
      ...(configFile ? { RELAY_CONFIG_FILE: resolve(relayRoot, configFile) } : {}),
    },
  });
  capturedRelayOutput.push(result.stdout, result.stderr);
  return result;
}

export interface RelayProcess {
  base: string;
  port: number;
  stop(signal?: NodeJS.Signals): Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
  start(): Promise<void>;
  env: NodeJS.ProcessEnv;
}

export interface RelayOptions {
  databaseUrl: string;
  configFile?: string;
  extraEnv?: NodeJS.ProcessEnv;
  scheme?: 'http' | 'https';
  healthCheck?: (base: string) => Promise<boolean>;
}

export const PHONE_ORIGIN = 'http://127.0.0.1:43001';

export async function startRelay(options: RelayOptions): Promise<RelayProcess> {
  const scheme = options.scheme ?? 'http';
  let port = Number(options.extraEnv?.RELAY_PORT ?? 0);
  let child: ChildProcess | null = null;
  let exit: Promise<{ code: number | null; signal: NodeJS.Signals | null }> = Promise.resolve({ code: null, signal: null });
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    RELAY_DATABASE_URL: options.databaseUrl,
    RELAY_DEMO_LOCAL: '1',
    PHONE_WEB_BASE_URL: `${PHONE_ORIGIN}/`,
    PHONE_RELAY_BASE_URL: 'http://127.0.0.1:43002/',
    VEHICLE_RELAY_BASE_URL: 'http://10.0.2.2:43002/',
    ...(options.configFile ? { RELAY_CONFIG_FILE: resolve(relayRoot, options.configFile) } : {}),
    ...options.extraEnv,
  };

  const start = async () => {
    const proc = spawn(process.execPath, ['src/main.ts'], {
      cwd: relayRoot,
      env: { ...env, RELAY_PORT: String(port) },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child = proc;
    exit = new Promise((done) => proc.once('exit', (code, signal) => done({ code, signal })));
    let stderr = '';
    const listening = new Promise<number>((done, fail) => {
      proc.stdout!.on('data', (chunk: Buffer) => {
        const text = chunk.toString();
        capturedRelayOutput.push(text);
        const match = /"event":"listening","port":(\d+)/.exec(text);
        if (match) done(Number(match[1]));
      });
      proc.stderr!.on('data', (chunk: Buffer) => {
        stderr += chunk.toString();
        capturedRelayOutput.push(chunk.toString());
      });
      proc.once('exit', (code) => fail(new Error(`relay exited early (${code}): ${stderr}`)));
    });
    port = await Promise.race([listening, sleep(15000).then(() => { throw new Error('relay did not start'); })]);
  };

  await start();
  return {
    get base() { return `${scheme}://127.0.0.1:${port}`; },
    get port() { return port; },
    env,
    start,
    stop: async (signal = 'SIGTERM') => {
      if (!child) return { code: null, signal: null };
      child.kill(signal);
      const result = await exit;
      child = null;
      return result;
    },
  };
}

/** Runs the entrypoint expecting it to refuse; returns how it died. */
export function startupOutcome(env: NodeJS.ProcessEnv) {
  const result = spawnSync(process.execPath, ['src/main.ts'], { cwd: relayRoot, encoding: 'utf8', env: { ...process.env, ...env }, timeout: 15000 });
  capturedRelayOutput.push(result.stdout, result.stderr);
  return { status: result.status, refused: result.status !== 0, message: /Error: (.*)/.exec(result.stderr)?.[1] ?? '' };
}
