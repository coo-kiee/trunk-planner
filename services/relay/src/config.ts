// Versioned relay configuration and process environment. Everything is validated before the socket opens.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export interface RateRule { capacity: number; refillPerMinute: number }
export interface RelayLimits {
  claimBodyBytes: number;
  ackBodyBytes: number;
  draftJsonBodyBytes: number;
  photoDecodedBytes: number;
  photoMaxDimensionPx: number;
  photoMimeAllowlist: string[];
  maxActiveDraftsPerPairing: number;
  maxDraftRowsPerPairing: number;
  maxActiveBytesPerPairing: number;
  maxActivePairings: number;
  listPageMax: number;
}
export interface RelayConfig {
  relayConfigVersion: number;
  profile: 'production' | 'test';
  contractsConfigurationRevision: number;
  credentialTtlSeconds: number;
  consentVersions: string[];
  limits: RelayLimits;
  rateLimit: { createPairing: RateRule; claim: RateRule; authenticated: RateRule };
  cleanup: { intervalSeconds: number; batchSize: number };
  /** Short TTLs for real-clock boundary tests. Only accepted for profile=test. */
  ttlOverrideSeconds?: { pairing: number; draft: number; ack: number };
  ttl: { pairing: number; draft: number; ack: number };
  sha256: string;
}

const here = dirname(fileURLToPath(import.meta.url));
export const relayRoot = resolve(here, '..');
export const defaultConfigFile = resolve(relayRoot, 'config/relay-config-v1.json');

function readContractsConfiguration(): { revision: number; pairingTtlSeconds: number; draftTtlSeconds: number; ackTtlSeconds: number } {
  const require = createRequire(import.meta.url);
  return require('@cargo/contracts/configuration-v1.json');
}

export function loadConfig(file: string = defaultConfigFile): RelayConfig {
  const text = readFileSync(file, 'utf8');
  const raw = JSON.parse(text) as Omit<RelayConfig, 'ttl' | 'sha256'>;
  const contracts = readContractsConfiguration();
  if (contracts.revision !== raw.contractsConfigurationRevision) {
    throw new Error(`relay config expects contracts configuration revision ${raw.contractsConfigurationRevision}, found ${contracts.revision}`);
  }
  if (raw.ttlOverrideSeconds && raw.profile !== 'test') throw new Error('ttlOverrideSeconds requires profile=test');
  const ttl = raw.ttlOverrideSeconds ?? {
    pairing: contracts.pairingTtlSeconds,
    draft: contracts.draftTtlSeconds,
    ack: contracts.ackTtlSeconds,
  };
  return { ...raw, ttl, sha256: createHash('sha256').update(text).digest('hex') };
}

export interface RelayEnv {
  databaseUrl: string;
  bindHost: string;
  port: number;
  demoLocal: boolean;
  tls: { certFile: string; keyFile: string } | null;
  phoneWebBaseUrl: URL;
  phoneRelayBaseUrl: URL;
  vehicleRelayBaseUrl: URL;
  configFile: string;
}

const LOOPBACK = new Set(['127.0.0.1', '::1', 'localhost']);

function requiredEnv(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new Error(`missing required environment variable ${name}`);
  return value;
}

function baseUrl(env: NodeJS.ProcessEnv, name: string, demoLocal: boolean): URL {
  const url = new URL(requiredEnv(env, name));
  if (url.protocol !== 'https:' && !(demoLocal && url.protocol === 'http:')) {
    throw new Error(`${name} must use https (http only with RELAY_DEMO_LOCAL=1)`);
  }
  if (url.search || url.hash || url.username || url.password) throw new Error(`${name} must not carry query, fragment or credentials`);
  return url;
}

/** Enforces: TLS by default; cleartext only for an explicit loopback demo; encrypted DB link off-host. */
export function readEnv(env: NodeJS.ProcessEnv = process.env): RelayEnv {
  const demoLocal = env.RELAY_DEMO_LOCAL === '1';
  const bindHost = env.RELAY_BIND_HOST ?? '127.0.0.1';
  const certFile = env.RELAY_TLS_CERT_FILE;
  const keyFile = env.RELAY_TLS_KEY_FILE;
  if (Boolean(certFile) !== Boolean(keyFile)) throw new Error('RELAY_TLS_CERT_FILE and RELAY_TLS_KEY_FILE must be set together');
  const tls = certFile && keyFile ? { certFile, keyFile } : null;
  if (!tls && !(demoLocal && LOOPBACK.has(bindHost))) {
    throw new Error('refusing cleartext listener: TLS is required unless RELAY_DEMO_LOCAL=1 on a loopback address');
  }
  const databaseUrl = requiredEnv(env, 'RELAY_DATABASE_URL');
  // URL errors echo their input; this one holds the database password.
  if (!URL.canParse(databaseUrl)) throw new Error('RELAY_DATABASE_URL is not a valid URL');
  const db = new URL(databaseUrl);
  if (!LOOPBACK.has(db.hostname) && db.searchParams.get('sslmode') !== 'verify-full') {
    throw new Error('a non-loopback database requires sslmode=verify-full');
  }
  return {
    databaseUrl,
    bindHost,
    port: Number(env.RELAY_PORT ?? 0),
    demoLocal,
    tls,
    phoneWebBaseUrl: baseUrl(env, 'PHONE_WEB_BASE_URL', demoLocal),
    phoneRelayBaseUrl: baseUrl(env, 'PHONE_RELAY_BASE_URL', demoLocal),
    vehicleRelayBaseUrl: baseUrl(env, 'VEHICLE_RELAY_BASE_URL', demoLocal),
    configFile: env.RELAY_CONFIG_FILE ?? defaultConfigFile,
  };
}

/** A shortened-TTL profile must never serve anything except a loopback demo. */
export function assertProfileAllowed(config: RelayConfig, env: RelayEnv): void {
  if (config.profile !== 'production' && !env.demoLocal) throw new Error('test configuration profile requires RELAY_DEMO_LOCAL=1');
}
