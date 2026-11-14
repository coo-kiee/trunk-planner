// migrate [--down-one --confirm-destroys-data]. Env: RELAY_MIGRATION_DATABASE_URL, RELAY_RUNTIME_ROLE, RELAY_CONFIG_FILE.
import { defaultConfigFile, loadConfig } from '../config.ts';
import { grantRuntimeRole, migrateDownOne, migrateUp, seedConfiguration } from '../db/migrate.ts';

const url = process.env.RELAY_MIGRATION_DATABASE_URL;
if (!url) throw new Error('RELAY_MIGRATION_DATABASE_URL is required');
const args = process.argv.slice(2);

if (args.includes('--down-one')) {
  const reverted = await migrateDownOne(url, args.includes('--confirm-destroys-data'));
  console.log(JSON.stringify({ reverted }));
} else {
  const runtimeRole = process.env.RELAY_RUNTIME_ROLE;
  if (!runtimeRole) throw new Error('RELAY_RUNTIME_ROLE is required');
  const result = await migrateUp(url);
  await seedConfiguration(url, loadConfig(process.env.RELAY_CONFIG_FILE ?? defaultConfigFile));
  await grantRuntimeRole(url, runtimeRole);
  console.log(JSON.stringify(result));
}
