// Shared per-suite fixture: one isolated cluster, one database, one relay process with load-friendly limits.
import { after, before } from 'node:test';
import pg from 'pg';
import { createCluster, portIsFree, type PgCluster } from './pg-harness.ts';
import { migrate, startRelay, type RelayProcess } from './relay-process.ts';
import { recordEnvironment } from './recorder.ts';

export const DB = 'cargo_relay_test';
export const LOAD_CONFIG = 'test/fixtures/config-load.json';

export interface SuiteEnv { cluster: PgCluster; relay: RelayProcess; db: pg.Pool; base: string; extra: RelayProcess[]; pools: pg.Pool[] }
export const env = {} as SuiteEnv;

/** Reads a running relay's state directly, bypassing the HTTP API, as the runtime role. */
export function query<T extends pg.QueryResultRow = any>(sql: string, params: unknown[] = []) {
  return env.db.query<T>(sql, params);
}

export function installSuite(label: string, afterAll?: () => Promise<void> | void): void {
  before(async () => {
    env.extra = [];
    env.pools = [];
    env.cluster = await createCluster(label);
    await env.cluster.createDatabase(DB);
    const migrated = migrate(env.cluster, DB, LOAD_CONFIG);
    if (migrated.status !== 0) throw new Error(`migrate failed: ${migrated.stderr}`);
    env.relay = await startRelay({ databaseUrl: env.cluster.runtimeUrl(DB), configFile: LOAD_CONFIG });
    env.base = env.relay.base;
    env.db = new pg.Pool({ connectionString: env.cluster.runtimeUrl(DB), max: 3 });
    env.db.on('error', () => undefined);
    recordEnvironment({
      postgres_select_version: env.cluster.version,
      postgres_binary_version: env.cluster.binVersion,
      postgres_port: env.cluster.port,
      postmaster_pid_at_start: env.cluster.postmasterPid(),
      postgres_bind: '127.0.0.1 tcp, unix sockets disabled, scram-sha-256',
      initdb_locale: 'C, encoding UTF8, LC_ALL=en_US.UTF-8',
      data_directory_relative: `services/relay/.local-pg/${env.cluster.dir.split('/').pop()}/data`,
      relay_port: env.relay.port,
    });
  });

  after(async () => {
    const steps: (() => Promise<unknown> | unknown)[] = [
      () => afterAll?.(),
      () => env.relay?.stop(),
      () => Promise.all((env.extra ?? []).map((relay) => relay.stop())),
      () => env.db?.end(),
      () => Promise.all((env.pools ?? []).map((pool) => pool.end())),
    ];
    for (const step of steps) await Promise.resolve().then(step).catch(() => undefined);
    const port = env.cluster?.port;
    const pid = env.cluster?.postmasterPid();
    await env.cluster?.stop();
    recordEnvironment({ postmaster_pid_at_stop: pid, postgres_port_released: port ? await portIsFree(port) : null });
    env.cluster?.destroy();
  });
}
