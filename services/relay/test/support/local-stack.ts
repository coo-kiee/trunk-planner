// Isolated demo stack for the phone and vehicle integration: own PostgreSQL + real relay on loopback, removed at exit.
// Emulators reach it through 10.0.2.2, phones need separate local TLS. Not a hosting path; passwords are never printed.
import { createCluster } from './pg-harness.ts';
import { migrate, startRelay } from './relay-process.ts';

const database = 'cargo_relay_local';
const cluster = await createCluster('local');
await cluster.createDatabase(database);
const migrated = migrate(cluster, database);
if (migrated.status !== 0) throw new Error(`migrate failed: ${migrated.stderr}`);
// Phone and vehicle URLs differ per setup (emulator 10.0.2.2, phone LAN), so callers may set them independently.
const overrides = Object.fromEntries(
  ['PHONE_WEB_BASE_URL', 'PHONE_RELAY_BASE_URL', 'VEHICLE_RELAY_BASE_URL', 'RELAY_PORT']
    .filter((name) => process.env[name])
    .map((name) => [name, process.env[name]]),
);
const relay = await startRelay({ databaseUrl: cluster.runtimeUrl(database), extraEnv: overrides });
console.log(JSON.stringify({ event: 'local_stack_ready', relayBaseUrl: relay.base, relayPort: relay.port, postgresPort: cluster.port, profile: 'production', demoLocal: true, grade: 'real_postgres_local_integration' }));

let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  await relay.stop();
  await cluster.stop();
  cluster.destroy();
  process.exit(0);
}
process.on('SIGTERM', () => void stop());
process.on('SIGINT', () => void stop());
