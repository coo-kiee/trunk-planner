// Single relay process: loads versioned config, verifies it against the DB row, serves, sweeps TTLs, exits cleanly.
import { assertProfileAllowed, loadConfig, readEnv } from './config.ts';
import { runCleanup } from './db/cleanup.ts';
import { assertConfigurationSeeded } from './db/migrate.ts';
import { createPool } from './db/pool.ts';
import { createRelayServer } from './http/server.ts';
import { log } from './logger.ts';

const env = readEnv();
const config = loadConfig(env.configFile);
assertProfileAllowed(config, env);

const pool = createPool(env.databaseUrl, (cause) => log('database_pool_error', { errorClass: cause.constructor.name }));
await assertConfigurationSeeded(pool, config);

const server = createRelayServer(pool, config, env);
server.listen(env.port, env.bindHost, () => {
  const address = server.address();
  log('listening', {
    port: typeof address === 'object' && address ? address.port : env.port,
    tls: env.tls !== null,
    configVersion: config.relayConfigVersion,
    profile: config.profile,
  });
});

const sweep = setInterval(() => {
  runCleanup(pool, config.cleanup.batchSize)
    .then((result) => {
      if (result.expiredDrafts + result.deletedAcks + result.deletedPairings > 0) log('cleanup', { ...result });
    })
    .catch((cause: Error & { code?: string }) => log('cleanup_failed', { errorClass: cause.constructor.name, sqlState: cause.code }));
}, config.cleanup.intervalSeconds * 1000);
sweep.unref();

let stopping = false;
async function shutdown(): Promise<void> {
  if (stopping) return;
  stopping = true;
  clearInterval(sweep);
  log('shutdown');
  server.close();
  server.closeIdleConnections();
  const force = setTimeout(() => server.closeAllConnections(), 5000);
  force.unref();
  await new Promise((done) => server.once('close', done));
  await pool.end();
  process.exit(0);
}
process.on('SIGTERM', () => void shutdown());
process.on('SIGINT', () => void shutdown());
process.on('uncaughtException', (cause) => {
  log('fatal', { errorClass: cause.constructor.name });
  process.exit(1);
});
