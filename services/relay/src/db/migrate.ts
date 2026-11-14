// Forward migration runner: advisory lock, recorded checksums, optional single-step down for isolated databases.
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import pg from 'pg';
import { relayRoot, type RelayConfig } from '../config.ts';

const MIGRATION_LOCK = 7_203_114;
const migrationsDir = resolve(relayRoot, 'migrations');

interface MigrationFile { id: string; upSql: string; downSql: string; checksum: string }

function readMigrations(): MigrationFile[] {
  return readdirSync(migrationsDir)
    .filter((name) => name.endsWith('.up.sql'))
    .sort()
    .map((name) => {
      const id = name.replace('.up.sql', '');
      const upSql = readFileSync(resolve(migrationsDir, name), 'utf8');
      const downSql = readFileSync(resolve(migrationsDir, `${id}.down.sql`), 'utf8');
      return { id, upSql, downSql, checksum: createHash('sha256').update(upSql).digest('hex') };
    });
}

async function withMigrationLock<T>(databaseUrl: string, work: (client: pg.Client) => Promise<T>): Promise<T> {
  const client = new pg.Client({ connectionString: databaseUrl });
  await client.connect();
  try {
    await client.query('SELECT pg_advisory_lock($1)', [MIGRATION_LOCK]);
    await client.query(`CREATE TABLE IF NOT EXISTS relay_schema_migration (
      id text PRIMARY KEY, checksum char(64) NOT NULL, applied_at timestamptz NOT NULL DEFAULT clock_timestamp())`);
    return await work(client);
  } finally {
    await client.end();
  }
}

export interface MigrateResult { applied: string[]; skipped: string[] }

export async function migrateUp(databaseUrl: string): Promise<MigrateResult> {
  return withMigrationLock(databaseUrl, async (client) => {
    const recorded = await client.query<{ id: string; checksum: string }>('SELECT id, checksum FROM relay_schema_migration');
    const known = new Map(recorded.rows.map((row) => [row.id, row.checksum]));
    const result: MigrateResult = { applied: [], skipped: [] };
    for (const migration of readMigrations()) {
      const checksum = known.get(migration.id);
      if (checksum !== undefined) {
        if (checksum !== migration.checksum) throw new Error(`migration ${migration.id} changed after it was applied`);
        result.skipped.push(migration.id);
        continue;
      }
      await client.query('BEGIN');
      try {
        await client.query(migration.upSql);
        await client.query('INSERT INTO relay_schema_migration (id, checksum) VALUES ($1, $2)', [migration.id, migration.checksum]);
        await client.query('COMMIT');
      } catch (cause) {
        await client.query('ROLLBACK');
        throw cause;
      }
      result.applied.push(migration.id);
    }
    return result;
  });
}

/** Reverts the newest migration. Destroys data: refused unless the caller explicitly confirms an isolated database. */
export async function migrateDownOne(databaseUrl: string, confirmDestroysData: boolean): Promise<string | null> {
  if (!confirmDestroysData) throw new Error('down migration requires explicit confirmation');
  return withMigrationLock(databaseUrl, async (client) => {
    const newest = await client.query<{ id: string }>('SELECT id FROM relay_schema_migration ORDER BY id DESC LIMIT 1');
    const id = newest.rows[0]?.id;
    if (!id) return null;
    const migration = readMigrations().find((candidate) => candidate.id === id);
    if (!migration) throw new Error(`no down script for ${id}`);
    await client.query('BEGIN');
    try {
      await client.query(migration.downSql);
      await client.query('DELETE FROM relay_schema_migration WHERE id = $1', [id]);
      await client.query('COMMIT');
    } catch (cause) {
      await client.query('ROLLBACK');
      throw cause;
    }
    return id;
  });
}

const quoteIdent = (name: string) => `"${name.replaceAll('"', '""')}"`;

/** Runtime role gets row access only: it cannot change schema or rewrite configuration versions. */
export async function grantRuntimeRole(databaseUrl: string, runtimeRole: string): Promise<void> {
  const client = new pg.Client({ connectionString: databaseUrl });
  await client.connect();
  try {
    const role = quoteIdent(runtimeRole);
    await client.query(`GRANT USAGE ON SCHEMA public TO ${role}`);
    await client.query(`GRANT SELECT ON relay_configuration TO ${role}`);
    await client.query(`GRANT SELECT, INSERT, UPDATE, DELETE ON relay_pairing, relay_credential, relay_draft, relay_draft_photo, relay_ack TO ${role}`);
  } finally {
    await client.end();
  }
}

export async function seedConfiguration(databaseUrl: string, config: RelayConfig): Promise<void> {
  const client = new pg.Client({ connectionString: databaseUrl });
  await client.connect();
  try {
    await client.query(
      `INSERT INTO relay_configuration (version, config_sha256, contracts_revision, profile, pairing_ttl_seconds,
                                        draft_ttl_seconds, ack_ttl_seconds, credential_ttl_seconds, limits)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (version) DO NOTHING`,
      [config.relayConfigVersion, config.sha256, config.contractsConfigurationRevision, config.profile,
        config.ttl.pairing, config.ttl.draft, config.ttl.ack, config.credentialTtlSeconds, JSON.stringify(config.limits)],
    );
  } finally {
    await client.end();
  }
}

/** Refuses to serve when the file and the versioned row disagree, so one version number means one set of limits. */
export async function assertConfigurationSeeded(query: pg.Pool, config: RelayConfig): Promise<void> {
  const found = await query.query<{ config_sha256: string }>('SELECT config_sha256 FROM relay_configuration WHERE version = $1', [config.relayConfigVersion]);
  const row = found.rows[0];
  if (!row) throw new Error(`relay configuration version ${config.relayConfigVersion} not seeded; run the migrate command`);
  if (row.config_sha256 !== config.sha256) throw new Error(`relay configuration version ${config.relayConfigVersion} differs from the seeded row`);
}
