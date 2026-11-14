// Foreground-only transfer. Runs on load, online, visibility, pageshow and the retry button. There is no service worker.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { OutboxEntry } from '../../entities/outbox/model.ts';
import type { PairingRecord } from '../../entities/pairing/model.ts';
import { applyEntryEvent, loadSnapshot } from '../../shared/storage/repository.ts';
import { runSendCycle } from './send-cycle.ts';

interface Options {
  relayBaseUrl: string | null;
  allowed: boolean;
  pairing: PairingRecord | null;
  onChanged: () => void;
}

const CHANNEL = 'cargo-phone-outbox';

/** Holds a browser-wide lock when available, so two tabs do not run the same pass. Duplicate sends would still be idempotent. */
async function withTabLock(work: () => Promise<void>): Promise<void> {
  if (!navigator.locks) return work();
  await navigator.locks.request('cargo-phone-outbox', { ifAvailable: true }, async (lock) => {
    if (lock) await work();
  });
}

/** After a reload, a "sending" entry may or may not have arrived. It goes back to waiting and the idempotent key sorts it out. */
export async function recoverInterrupted(entries: OutboxEntry[]): Promise<void> {
  const at = new Date().toISOString();
  await Promise.all(entries.filter((entry) => entry.status === 'sending').map((entry) => applyEntryEvent(entry.key, { type: 'RECOVER_AFTER_RELOAD', at })));
}

export function useOutboxEngine({ relayBaseUrl, allowed, pairing, onChanged }: Options) {
  const [running, setRunning] = useState(false);
  const runningRef = useRef(false);
  const rerunRef = useRef<{ force: boolean } | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const readsRef = useRef(new Map<string, { count: number; nextAt: number }>());
  const channelRef = useRef<BroadcastChannel | null>(null);
  const pairingRef = useRef(pairing);
  pairingRef.current = pairing;

  const announce = useCallback(() => {
    onChanged();
    channelRef.current?.postMessage('changed');
  }, [onChanged]);

  const run = useCallback(async (force: boolean) => {
    if (runningRef.current) {
      rerunRef.current = { force: force || rerunRef.current?.force === true };
      return;
    }
    const current = pairingRef.current;
    if (!allowed || !relayBaseUrl || !current || current.state !== 'ACTIVE' || !current.phoneToken) return;
    if (navigator.onLine === false) return;

    if (timerRef.current) clearTimeout(timerRef.current);
    runningRef.current = true;
    setRunning(true);
    const controller = new AbortController();
    abortRef.current = controller;
    if (force) readsRef.current.clear();
    let nextDue: number | null = null;
    try {
      await withTabLock(async () => {
        const snapshot = await loadSnapshot();
        nextDue = await runSendCycle(snapshot.entries, { relayBaseUrl, pairing: current, force, signal: controller.signal, reads: readsRef.current, onProgress: announce });
      });
    } catch {
      // A storage failure is surfaced by the next snapshot read; the pass simply ends without claiming progress.
    } finally {
      runningRef.current = false;
      setRunning(false);
      abortRef.current = null;
    }
    announce();
    const rerun = rerunRef.current;
    rerunRef.current = null;
    if (rerun) {
      void run(rerun.force);
    } else if (nextDue !== null && document.visibilityState === 'visible') {
      timerRef.current = setTimeout(() => void run(false), Math.max(0, nextDue - Date.now()));
    }
  }, [allowed, relayBaseUrl, announce]);

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const channel = new BroadcastChannel(CHANNEL);
    channel.onmessage = () => onChanged();
    channelRef.current = channel;
    return () => {
      channel.close();
      channelRef.current = null;
    };
  }, [onChanged]);

  useEffect(() => {
    void run(false);
    const wake = () => void run(true);
    const onVisible = () => { if (document.visibilityState === 'visible') wake(); };
    window.addEventListener('online', wake);
    window.addEventListener('pageshow', wake);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('online', wake);
      window.removeEventListener('pageshow', wake);
      document.removeEventListener('visibilitychange', onVisible);
      if (timerRef.current) clearTimeout(timerRef.current);
      abortRef.current?.abort();
    };
  }, [run, pairing?.pairingId, pairing?.state]);

  // Called after every local change: other tabs must hear about it even when this tab cannot send (offline, no pairing).
  const retryNow = useCallback(() => {
    channelRef.current?.postMessage('changed');
    void run(true);
  }, [run]);
  const kick = useCallback(() => void run(false), [run]);
  return { running, retryNow, kick };
}
