// Classifies relay outcomes and computes foreground backoff. No service worker and no fixed polling contract: only an open page retries.
import type { FailureKind } from './model.ts';

export type RelayFailure = { kind: 'network' } | { kind: 'http'; status: number; code: string; details: Record<string, unknown> };

export type FailureAction = { action: 'retry'; kind: FailureKind; code: string } | { action: 'stop'; code: string };

/** The browser cannot tell an offline relay from a refused origin, so both are "connection failure (settings or network)". */
export function classifyFailure(failure: RelayFailure): FailureAction {
  if (failure.kind === 'network') return { action: 'retry', kind: 'NETWORK', code: 'CONNECTION_FAILED' };
  if (failure.code === 'RATE_LIMITED') return { action: 'retry', kind: 'RATE_LIMITED', code: failure.code };
  if (failure.status >= 500) return { action: 'retry', kind: 'SERVER', code: failure.code };
  return { action: 'stop', code: failure.code };
}

export const AUTO_RETRY_LIMIT = 6;
const BASE_MS: Record<FailureKind, number> = { NETWORK: 2_000, SERVER: 4_000, RATE_LIMITED: 15_000 };
const CAP_MS = 60_000;

/** Returns null once automatic retry stops. A foreground event or the retry button restarts it. */
export function nextRetryDelayMs(kind: FailureKind, attempts: number): number | null {
  if (attempts >= AUTO_RETRY_LIMIT) return null;
  return Math.min(BASE_MS[kind] * 2 ** Math.max(0, attempts - 1), CAP_MS);
}

export const STATUS_READ_MIN_MS = 3_000;
export const STATUS_READ_CAP_MS = 30_000;
export function nextStatusDelayMs(reads: number): number {
  return Math.min(STATUS_READ_MIN_MS * 2 ** Math.max(0, reads), STATUS_READ_CAP_MS);
}
