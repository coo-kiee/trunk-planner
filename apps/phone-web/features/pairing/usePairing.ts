// P0 effects: claim, revoke and wipe. Transfer stays blocked by the environment judgement, whatever arrives in the URL.
import { useCallback, useEffect, useState } from 'react';
import { revokePairing } from '../../shared/api/relay-client.ts';
import type { PairingRecord } from '../../entities/pairing/model.ts';
import { clearPairing, savePairing, wipeLocalData } from '../../shared/storage/repository.ts';
import { PAIRING_TOKEN_EVENT } from './bootstrap-script.ts';
import { claimOnce, forgetToken, hadMalformedToken, hasPairingToken } from './token-session.ts';

export type ClaimState =
  | { kind: 'IDLE' }
  | { kind: 'CLAIMING' }
  | { kind: 'CLAIMED' }
  | { kind: 'FAILED'; code: string; retryable: boolean };

interface Options {
  relayBaseUrl: string | null;
  /** Null until the browser's capabilities have been read: not knowing yet is not a block. */
  transferAllowed: boolean | null;
  storageReady: boolean;
  onChanged: () => void;
  onRevoked: () => Promise<void>;
  onClaimed: (pairingId: string) => Promise<void>;
  /** A new QR arrived in this already open tab. */
  onNewToken: () => void;
}

export function usePairing({ relayBaseUrl, transferAllowed, storageReady, onChanged, onRevoked, onClaimed, onNewToken }: Options) {
  const [claim, setClaim] = useState<ClaimState>({ kind: 'IDLE' });
  const [revoke, setRevoke] = useState<{ kind: 'IDLE' | 'WORKING' | 'DONE' } | { kind: 'FAILED'; code: string }>({ kind: 'IDLE' });

  const runClaim = useCallback(async () => {
    if (!relayBaseUrl) return;
    const pending = claimOnce(relayBaseUrl);
    if (!pending) return;
    setClaim({ kind: 'CLAIMING' });
    const result = await pending;
    if (!result.ok) {
      const code = result.kind === 'network' ? 'CONNECTION_FAILED' : result.code;
      setClaim({ kind: 'FAILED', code, retryable: hasPairingToken() });
      return;
    }
    const record: PairingRecord = { ...result.body, claimedAt: new Date().toISOString(), state: 'ACTIVE' };
    try {
      await savePairing(record);
    } catch {
      // The relay spent the token, so it cannot be claimed again; only a new QR recovers this.
      setClaim({ kind: 'FAILED', code: 'LOCAL_SAVE_FAILED', retryable: false });
      return;
    }
    await onClaimed(record.pairingId).catch(() => undefined);
    setClaim({ kind: 'CLAIMED' });
    onChanged();
  }, [relayBaseUrl, onChanged, onClaimed]);

  const [tokenArrivals, setTokenArrivals] = useState(0);
  useEffect(() => {
    const onToken = () => {
      setClaim({ kind: 'IDLE' });
      setTokenArrivals((count) => count + 1);
      onNewToken();
    };
    window.addEventListener(PAIRING_TOKEN_EVENT, onToken);
    return () => window.removeEventListener(PAIRING_TOKEN_EVENT, onToken);
  }, [onNewToken]);

  useEffect(() => {
    if (hadMalformedToken()) setClaim({ kind: 'FAILED', code: 'QR_MALFORMED', retryable: false });
    if (!hasPairingToken() || transferAllowed === null) return;
    if (!transferAllowed) {
      forgetToken(); // A blocked environment must not keep or use the token.
      setClaim({ kind: 'FAILED', code: 'ENVIRONMENT_BLOCKED', retryable: false });
      return;
    }
    if (storageReady) void runClaim();
  }, [transferAllowed, storageReady, runClaim, tokenArrivals]);

  const disconnect = useCallback(async (record: PairingRecord, options: { localOnly: boolean }) => {
    setRevoke({ kind: 'WORKING' });
    if (!options.localOnly && relayBaseUrl) {
      const result = await revokePairing(relayBaseUrl, record.phoneToken);
      const alreadyGone = !result.ok && result.kind === 'http' && ['PAIRING_REVOKED', 'CREDENTIAL_EXPIRED', 'UNAUTHENTICATED'].includes(result.code);
      if (!result.ok && !alreadyGone) {
        setRevoke({ kind: 'FAILED', code: result.kind === 'network' ? 'CONNECTION_FAILED' : result.code });
        return;
      }
    }
    await savePairing({ ...record, state: 'REVOKED', phoneToken: '' });
    await onRevoked();
    setRevoke({ kind: 'DONE' });
    onChanged();
  }, [relayBaseUrl, onChanged, onRevoked]);

  const forget = useCallback(async () => {
    await clearPairing();
    onChanged();
  }, [onChanged]);

  const wipe = useCallback(async () => {
    await wipeLocalData();
    setRevoke({ kind: 'IDLE' });
    onChanged();
  }, [onChanged]);

  return { claim, revoke, retryClaim: runClaim, disconnect, forget, wipe };
}
