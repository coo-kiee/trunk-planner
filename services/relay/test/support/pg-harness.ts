// Owns one isolated PostgreSQL under services/relay/.local-pg using the exact-pinned local binaries.
// No system install, no shared data directory, TCP on 127.0.0.1 with SCRAM, and it only ever signals its own child.
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { closeSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { resolve } from 'node:path';
import pg from 'pg';
import { relayRoot } from '../../src/config.ts';

const RESERVED_PORTS = new Set([3100, 3101, 23000, 23120, 5432, 5554, 5555]);

export async function freePort(): Promise<number> {
  for (;;) {
    const port = await new Promise<number>((done, fail) => {
      const probe = createServer();
      probe.once('error', fail);
      probe.listen(0, '127.0.0.1', () => {
        const address = probe.address();
        const chosen = typeof address === 'object' && address ? address.port : 0;
        probe.close(() => done(chosen));
      });
    });
    if (!RESERVED_PORTS.has(port)) return port;
  }
}

export async function portIsFree(port: number): Promise<boolean> {
  return new Promise((done) => {
    const probe = createServer();
    probe.once('error', () => done(false));
    probe.listen(port, '127.0.0.1', () => probe.close(() => done(true)));
  });
}

export const sleep = (ms: number) => new Promise<void>((done) => setTimeout(done, ms));

function nativeBin(): string {
  // The package has no exports entry for its binaries; this exact path belongs to the pinned local install.
  return process.env.RELAY_TEST_PG_BIN ?? resolve(relayRoot, `node_modules/@embedded-postgres/${process.platform}-${process.arch}/native/bin`);
}

export interface PgCluster {
  port: number;
  dir: string;
  dataDir: string;
  logFile: string;
  adminUrl(database?: string): string;
  ownerUrl(database: string): string;
  runtimeUrl(database: string): string;
  ownerPassword: string;
  runtimePassword: string;
  postmasterPid(): number;
  version: string;
  start(): Promise<void>;
  killPostmaster(signal: NodeJS.Signals): Promise<void>;
  stop(): Promise<void>;
  createDatabase(name: string): Promise<void>;
  admin<T>(work: (client: pg.Client) => Promise<T>, database?: string): Promise<T>;
  destroy(): void;
  binVersion: string;
}

export async function createCluster(label: string): Promise<PgCluster> {
  const bin = nativeBin();
  const stamp = `${label}-${process.pid}-${randomBytes(3).toString('hex')}`;
  const dir = resolve(relayRoot, '.local-pg', stamp);
  const dataDir = resolve(dir, 'data');
  const logFile = resolve(dir, 'postgres.log');
  mkdirSync(dir, { recursive: true, mode: 0o700 });

  const adminPassword = randomBytes(24).toString('base64url');
  const ownerPassword = randomBytes(24).toString('base64url');
  const runtimePassword = randomBytes(24).toString('base64url');
  const pwfile = resolve(dir, 'pwfile');
  writeFileSync(pwfile, adminPassword, { mode: 0o600 });
  const pgEnv = { ...process.env, LC_ALL: 'en_US.UTF-8' };
  const init = spawnSync(resolve(bin, 'initdb'), ['-D', dataDir, '-U', 'relay_admin', '--pwfile', pwfile, '-A', 'scram-sha-256', '--locale=C', '-E', 'UTF8'], { env: pgEnv, encoding: 'utf8' });
  rmSync(pwfile, { force: true });
  if (init.status !== 0) throw new Error(`initdb failed (${init.status}): ${init.stderr}`);

  const port = await freePort();
  let child: ChildProcess | null = null;
  let exited: Promise<void> = Promise.resolve();

  const url = (user: string, password: string, database: string) => `postgres://${user}:${password}@127.0.0.1:${port}/${database}`;
  const adminUrl = (database = 'postgres') => url('relay_admin', adminPassword, database);

  const waitReady = async () => {
    const deadline = Date.now() + 30_000;
    for (;;) {
      const client = new pg.Client({ connectionString: adminUrl() });
      try {
        await client.connect();
        await client.query('SELECT 1');
        await client.end();
        return;
      } catch (cause) {
        await client.end().catch(() => undefined);
        if (Date.now() > deadline) throw cause;
        await sleep(100);
      }
    }
  };

  const start = async () => {
    const out = openSync(logFile, 'a');
    // No unix socket: macOS limits its path length. Values never go to the log, even when a statement fails.
    const proc = spawn(resolve(bin, 'postgres'), [
      '-D', dataDir, '-p', String(port), '-h', '127.0.0.1', '-k', '',
      '-c', 'log_statement=none', '-c', 'log_parameter_max_length_on_error=0', '-c', 'log_parameter_max_length=0',
      '-c', 'log_min_error_statement=panic', '-c', 'fsync=on', '-c', 'password_encryption=scram-sha-256',
    ], { env: pgEnv, stdio: ['ignore', out, out] });
    closeSync(out);
    child = proc;
    exited = new Promise((done) => proc.once('exit', () => done()));
    await waitReady();
  };

  const admin = async <T>(work: (client: pg.Client) => Promise<T>, database?: string): Promise<T> => {
    const client = new pg.Client({ connectionString: adminUrl(database) });
    await client.connect();
    try {
      return await work(client);
    } finally {
      await client.end();
    }
  };

  const stopWith = async (signal: NodeJS.Signals) => {
    if (!child) return;
    const proc = child;
    child = null;
    proc.kill(signal);
    await exited;
  };

  await start();
  await admin(async (client) => {
    await client.query(`CREATE ROLE relay_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${ownerPassword}'`);
    await client.query(`CREATE ROLE relay_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${runtimePassword}'`);
  });
  const version = await admin(async (client) => (await client.query<{ version: string }>('SELECT version()')).rows[0]!.version);
  const binVersion = spawnSync(resolve(bin, 'postgres'), ['--version'], { encoding: 'utf8' }).stdout.trim();

  return {
    port, dir, dataDir, logFile, version, binVersion, ownerPassword, runtimePassword,
    adminUrl,
    ownerUrl: (database) => url('relay_owner', ownerPassword, database),
    runtimeUrl: (database) => url('relay_app', runtimePassword, database),
    postmasterPid: () => Number(readFileSync(resolve(dataDir, 'postmaster.pid'), 'utf8').split('\n')[0]),
    start,
    killPostmaster: stopWith,
    stop: () => stopWith('SIGINT'),
    createDatabase: (name) => admin(async (client) => {
      await client.query(`CREATE DATABASE ${name} OWNER relay_owner`);
    }),
    admin,
    destroy: () => rmSync(dir, { recursive: true, force: true }),
  };
}
