// Allowlist logger: fields not named here are dropped, so a token, URL, body or SQL value cannot reach logs by accident.
const ALLOWED_FIELDS = new Set([
  'requestId', 'method', 'route', 'status', 'code', 'durationMs', 'port', 'tls',
  'sqlState', 'errorClass', 'configVersion', 'profile',
  'expiredDrafts', 'deletedAcks', 'deletedPairings',
]);

export type LogFields = Record<string, string | number | boolean | undefined>;

export function log(event: string, fields: LogFields = {}): void {
  const line: LogFields & { ts: string; event: string } = { ts: new Date().toISOString(), event };
  for (const [key, value] of Object.entries(fields)) {
    if (ALLOWED_FIELDS.has(key) && value !== undefined) line[key] = value;
  }
  process.stdout.write(`${JSON.stringify(line)}\n`);
}
