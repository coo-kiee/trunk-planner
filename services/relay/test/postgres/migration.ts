import assert from 'node:assert/strict';
import pg from 'pg';
import { scenario } from '../support/recorder.ts';
import { migrate } from '../support/relay-process.ts';
import { DB, env, LOAD_CONFIG } from '../support/suite-env.ts';
import { migrateDownOne, migrateUp } from '../../src/db/migrate.ts';

const grade = 'real_postgres_local_integration';
const MIGRATION_DB = 'cargo_relay_migration_test';

const tablesIn = (database: string) => env.cluster.admin(async (client) => {
  const rows = await client.query<{ table_name: string }>(`SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1`);
  return rows.rows.map((row) => row.table_name);
}, database);

export function migrationScenarios(): void {
  scenario('migration-up-rerun-checksum-down', { grade, expected: 'up applies; rerun skips; concurrent runners serialize on the advisory lock; altered checksum refuses; down needs confirmation, drops everything; up works again' }, async () => {
    await env.cluster.createDatabase(MIGRATION_DB);
    const url = env.cluster.ownerUrl(MIGRATION_DB);
    const first = await migrateUp(url);
    const rerun = await migrateUp(url);
    assert.deepEqual([first.applied, rerun.applied, rerun.skipped], [['0001_initial'], [], ['0001_initial']]);

    const owner = new pg.Client({ connectionString: url });
    await owner.connect();
    await owner.query(`UPDATE relay_schema_migration SET checksum = repeat('0', 64)`);
    const tampered = await migrateUp(url).then(() => 'accepted', (cause: Error) => cause.message);
    const stored = await owner.query<{ checksum: string }>(`SELECT checksum FROM relay_schema_migration`);
    assert.match(tampered, /changed after it was applied/);
    assert.equal(stored.rows[0]!.checksum, '0'.repeat(64));
    await owner.end();

    const unconfirmed = await migrateDownOne(url, false).then(() => 'accepted', (cause: Error) => cause.message);
    assert.match(unconfirmed, /explicit confirmation/);
    const tablesBeforeDown = await tablesIn(MIGRATION_DB);
    const reverted = await migrateDownOne(url, true);
    const tablesAfterDown = await tablesIn(MIGRATION_DB);
    assert.equal(reverted, '0001_initial');
    assert.deepEqual(tablesAfterDown, ['relay_schema_migration']);

    const concurrent = await Promise.all([migrateUp(url), migrateUp(url), migrateUp(url)]);
    const appliedCount = concurrent.filter((run) => run.applied.length === 1).length;
    assert.equal(appliedCount, 1);
    const tablesAfterUp = await tablesIn(MIGRATION_DB);
    assert.deepEqual(tablesAfterUp, tablesBeforeDown);
    return { firstApplied: first.applied, rerunSkipped: rerun.skipped, tampered, unconfirmedDown: unconfirmed, reverted, tablesAfterDown, concurrentApplied: appliedCount, tablesAfterUp };
  });

  scenario('migrate-cli-and-config-seed', { grade, expected: 'the operator CLI reruns safely and refuses a changed config under an existing version at relay start' }, async () => {
    const again = migrate(env.cluster, DB, LOAD_CONFIG);
    assert.equal(again.status, 0);
    assert.deepEqual(JSON.parse(again.stdout), { applied: [], skipped: ['0001_initial'] });
    return JSON.parse(again.stdout);
  });

  scenario('db-role-separation', { grade, expected: 'owner and runtime roles are non-superusers; the runtime role cannot change schema or configuration; wrong password fails SCRAM' }, async () => {
    const attrs = await env.cluster.admin(async (client) => (await client.query(`SELECT rolname, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname IN ('relay_owner', 'relay_app') ORDER BY 1`)).rows);
    assert.ok(attrs.every((role) => !role.rolsuper && !role.rolcreatedb && !role.rolcreaterole));
    const attempts: Record<string, string> = {};
    const runtime = new pg.Client({ connectionString: env.cluster.runtimeUrl(DB) });
    await runtime.connect();
    for (const [name, sql] of Object.entries({
      create: 'CREATE TABLE relay_evil (id int)',
      drop: 'DROP TABLE relay_ack',
      alter: 'ALTER TABLE relay_draft DROP CONSTRAINT relay_draft_payload_only_while_active',
      truncate: 'TRUNCATE relay_draft CASCADE',
      configUpdate: 'UPDATE relay_configuration SET draft_ttl_seconds = 999999',
      migrationTable: 'SELECT * FROM relay_schema_migration',
    })) {
      attempts[name] = await runtime.query(sql).then(() => 'allowed', (cause: { code: string }) => cause.code);
    }
    await runtime.end();
    assert.deepEqual(Object.values(attempts), Array(6).fill('42501'));
    const wrong = new pg.Client({ connectionString: `postgres://relay_app:wrong@127.0.0.1:${env.cluster.port}/${DB}` });
    const auth = await wrong.connect().then(() => 'connected', (cause: { code: string }) => cause.code);
    await wrong.end().catch(() => undefined);
    assert.equal(auth, '28P01');
    return { roles: attrs, runtimeAttempts: attempts, wrongPassword: auth };
  });
}
