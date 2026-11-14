// Holds the one-time QR token in module memory only: never in storage, the DOM or a log.
// Module scope survives StrictMode double effects and remounts, so one token can start only one claim at a time.
import { claimPairing, type ClaimBody, type RelayResult } from '../../shared/api/relay-client.ts';
import { isQrTokenShaped } from '../../entities/pairing/model.ts';

declare global {
  interface Window { __cargoTakePairingToken?: () => string | null }
}

let token: string | null = null;
let inFlight: Promise<RelayResult<ClaimBody>> | null = null;
let malformed = false;

/** Moves a token from the bootstrap closure into module memory. A later QR in the same tab replaces the earlier one. */
function adoptBootstrapToken(): void {
  const raw = window.__cargoTakePairingToken?.() ?? null;
  if (raw === null) return;
  malformed = !isQrTokenShaped(raw);
  token = malformed ? null : raw;
}

export function hasPairingToken(): boolean {
  adoptBootstrapToken();
  return token !== null;
}

export function hadMalformedToken(): boolean {
  adoptBootstrapToken();
  return malformed;
}

export function claimOnce(relayBaseUrl: string): Promise<RelayResult<ClaimBody>> | null {
  adoptBootstrapToken();
  if (inFlight) return inFlight;
  if (!token) return null;
  const current = token;
  inFlight = claimPairing(relayBaseUrl, current).then((result) => {
    inFlight = null;
    // Keep the token only for a retry after a connection failure. Any answer from the relay finishes it.
    const retryable = !result.ok && (result.kind === 'network' || result.status >= 500 || result.code === 'RATE_LIMITED');
    if (!retryable) token = null;
    return result;
  });
  return inFlight;
}

export function forgetToken(): void {
  token = null;
}
