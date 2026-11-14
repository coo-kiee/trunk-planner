// One-shot TTL sweep for operators. Env: RELAY_DATABASE_URL, optional CLEANUP_BATCH_SIZE.
import { log } from '../logger.ts';
import { createPool } from '../db/pool.ts';
import { runCleanup } from '../db/cleanup.ts';

const url = process.env.RELAY_DATABASE_URL;
if (!url) throw new Error('RELAY_DATABASE_URL is required');
const pool = createPool(url, () => undefined);
try {
  log('cleanup', { ...(await runCleanup(pool, Number(process.env.CLEANUP_BATCH_SIZE ?? 500))) });
} finally {
  await pool.end();
}
