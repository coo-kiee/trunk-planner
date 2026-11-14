'use client';
// Composes P0~P3. Each screen owns its view; the state shared between them lives here.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { editorFromItem, type ItemEditor } from '../../entities/item/model.ts';
import { canRepack, type OutboxEntry } from '../../entities/outbox/model.ts';
import { viewPairing } from '../../entities/pairing/model.ts';
import { readEnvironmentBlocks } from '../../features/environment.ts';
import { useItemEditor } from '../../features/item-editor/useItemEditor.ts';
import { markOldPairingUnreadable, markPairingEnded, repackEntries } from '../../features/outbox/actions.ts';
import { reviseEntry } from '../../features/outbox/enqueue.ts';
import { recoverInterrupted, useOutboxEngine } from '../../features/outbox/useOutboxEngine.ts';
import { usePairing } from '../../features/pairing/usePairing.ts';
import { hasPairingToken } from '../../features/pairing/token-session.ts';
import type { RelayUrlCheck } from '../../shared/config/relay-url.ts';
import type { EnvironmentBlock } from '../../shared/security/environment.ts';
import { StorageError } from '../../shared/storage/idb.ts';
import { newUuid } from '../../shared/lib/uuid.ts';
import { deleteEntry, discardCorruptRecords, loadSnapshot, releasePhotoIfUnused, savePairing, type Snapshot } from '../../shared/storage/repository.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { ConnectScreen } from '../connect/ConnectScreen.tsx';
import { DimensionsScreen } from '../dimensions/DimensionsScreen.tsx';
import { OutboxScreen, type OutboxActions } from '../outbox/OutboxScreen.tsx';
import { PhotoShapeScreen } from '../photo-shape/PhotoShapeScreen.tsx';
import { StorageBanner, type StorageHealth } from './StorageBanner.tsx';
import styles from './shell.module.css';

type ScreenId = 'P0' | 'P1' | 'P2' | 'P3';
const STEPS: ReadonlyArray<{ id: ScreenId; label: string }> = [
  { id: 'P0', label: '연결' },
  { id: 'P1', label: '사진·형상' },
  { id: 'P2', label: '치수·조건' },
  { id: 'P3', label: '전송함' },
];

const EMPTY: Snapshot = { pairing: null, editor: null, entries: [], confirmed: [], corruptKeys: [] };

export function PhoneApp({ relay }: { relay: RelayUrlCheck }) {
  const [screen, setScreen] = useState<ScreenId | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  const [health, setHealth] = useState<StorageHealth>('OK');
  const [blocks, setBlocks] = useState<EnvironmentBlock[] | null>(null);
  const [online, setOnline] = useState(true);
  const [nowMs, setNowMs] = useState(0);
  const relayBaseUrl = relay.ok ? relay.url : null;

  const refresh = useCallback(async () => {
    try {
      const next = await loadSnapshot();
      setSnapshot(next);
      setHealth(next.corruptKeys.length > 0 ? 'CORRUPT' : 'OK');
      setNowMs(Date.now());
      return next;
    } catch (cause) {
      setHealth(cause instanceof StorageError ? cause.failure : 'UNAVAILABLE');
      return null;
    }
  }, []);

  const initialEditor = useMemo(() => (loaded && snapshot.editor ? { state: snapshot.editor.state, saveId: snapshot.editor.saveId } : null), [loaded]); // eslint-disable-line react-hooks/exhaustive-deps
  const editorApi = useItemEditor(initialEditor, loaded);

  // First load: read capabilities, then storage, then choose where to land.
  useEffect(() => {
    setBlocks(readEnvironmentBlocks(relay));
    setOnline(navigator.onLine);
    void (async () => {
      const first = await refresh();
      if (first) await recoverInterrupted(first.entries).catch(() => undefined);
      const next = (await refresh()) ?? first;
      const hasWork = (next?.entries.length ?? 0) > 0 || next?.editor != null;
      const paired = next?.pairing?.state === 'ACTIVE';
      setScreen(hasPairingToken() ? 'P0' : paired && (next?.entries.length ?? 0) > 0 ? 'P3' : paired || hasWork ? (next?.editor ? 'P2' : 'P1') : 'P0');
      setLoaded(true);
    })();
    const onOnline = () => setOnline(true);
    const onOffline = () => setOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, [relay, refresh]);

  const pairingView = viewPairing(snapshot.pairing, nowMs || Date.now());
  const activePairing = pairingView.kind === 'ACTIVE' ? pairingView.record : null;
  const transferAllowed = blocks !== null && blocks.length === 0;
  const onChanged = useCallback(() => void refresh(), [refresh]);

  const onRevoked = useCallback(async () => {
    const current = await loadSnapshot();
    if (current.pairing) await markPairingEnded(current.entries, current.pairing.pairingId, 'PAIRING_REVOKED');
  }, []);

  const onClaimed = useCallback(async (pairingId: string) => {
    const current = await loadSnapshot();
    await markOldPairingUnreadable(current.entries, pairingId);
  }, []);

  const onNewToken = useCallback(() => setScreen('P0'), []);
  const pairing = usePairing({ relayBaseUrl, transferAllowed: blocks === null ? null : transferAllowed, storageReady: loaded && health !== 'UNAVAILABLE', onChanged, onRevoked, onClaimed, onNewToken });
  const engine = useOutboxEngine({ relayBaseUrl, allowed: transferAllowed, pairing: activePairing, onChanged });

  // The credential's own clock also ends a pairing: record that, so received entries read "unknown" instead of waiting forever.
  const expiringRecord = snapshot.pairing?.state === 'ACTIVE' ? snapshot.pairing : null;
  useEffect(() => {
    if (!expiringRecord) return;
    const remaining = Date.parse(expiringRecord.credentialExpiresAt) - Date.now();
    const finish = async () => {
      await savePairing({ ...expiringRecord, state: 'EXPIRED' });
      const current = await loadSnapshot();
      await markPairingEnded(current.entries, expiringRecord.pairingId, 'CREDENTIAL_EXPIRED');
      await refresh();
    };
    if (remaining <= 0) {
      void finish();
      return;
    }
    // setTimeout overflows above 2^31 ms; credentials live for hours, so the cap is never reached in practice.
    const timer = setTimeout(() => void finish(), Math.min(remaining + 500, 2 ** 31 - 1));
    return () => clearTimeout(timer);
  }, [expiringRecord, refresh]);

  const setPhoto = useCallback(async (photoId: string | null) => {
    const previous = editorApi.editor?.photoId ?? null;
    editorApi.change('photoId', photoId);
    if (photoId === null) editorApi.change('photoConsent', false);
    await editorApi.flush();
    if (previous && previous !== photoId) await releasePhotoIfUnused(previous).catch(() => false);
  }, [editorApi]);

  const openEditorFrom = useCallback((entry: OutboxEntry, base: ItemEditor['base'], continues: boolean) => {
    const draftKey = continues ? { draftId: entry.draft.draftId, clientRevision: entry.draft.clientRevision } : null;
    editorApi.replace(editorFromItem(entry.draft.payload, draftKey, base, entry.photoId, entry.consent.photoConsent));
    setScreen('P2');
  }, [editorApi]);

  const actions: OutboxActions = useMemo(() => ({
    retryNow: engine.retryNow,
    repackAll: async () => {
      if (!activePairing) return;
      await repackEntries(snapshot.entries, activePairing.pairingId);
      await refresh();
      engine.retryNow();
    },
    resendNewRevision: async (entry, removePhoto) => {
      await reviseEntry(entry, snapshot.entries, { removePhoto });
      await refresh();
      engine.retryNow();
    },
    editContinuing: (entry) => openEditorFrom(entry, entry.draft.baseItemId && entry.draft.baseItemRevision ? { itemId: entry.draft.baseItemId, revision: entry.draft.baseItemRevision } : null, true),
    editOnAuthoritative: (entry) => {
      if (!entry.conflict) return;
      openEditorFrom(entry, { itemId: entry.conflict.authoritativeItemId, revision: entry.conflict.authoritativeRevision }, true);
    },
    editConfirmed: (entry, confirmed) => openEditorFrom(entry, { itemId: confirmed.acceptedItemId, revision: confirmed.acceptedRevision }, false),
    copyAsNewItem: (entry) => {
      const copy = editorFromItem(entry.draft.payload, null, null, entry.photoId, entry.consent.photoConsent);
      editorApi.replace({ ...copy, itemId: newUuid(), createdAt: new Date().toISOString() });
      setScreen('P1');
    },
    removeLocal: async (entry) => {
      await deleteEntry(entry.key);
      if (entry.photoId) await releasePhotoIfUnused(entry.photoId).catch(() => false);
      await refresh();
    },
  }), [activePairing, editorApi, engine, openEditorFrom, refresh, snapshot.entries]);

  const repackable = activePairing ? snapshot.entries.filter((entry) => canRepack(entry, activePairing.pairingId)) : [];

  return (
    <main className={styles.page} data-screen={screen ?? 'loading'} data-loaded={loaded}>
      <h1 className={styles.appTitle}>물품 등록</h1>
      <nav className={styles.steps} aria-label="등록 단계">
        {STEPS.map((step) => (
          <Button key={step.id} variant="step" aria-current={screen === step.id ? 'step' : undefined} onClick={() => { void editorApi.flush(); void refresh(); setScreen(step.id); }} data-step={step.id}>
            {step.label}
          </Button>
        ))}
      </nav>
      <StorageBanner health={health} corruptCount={snapshot.corruptKeys.length} online={online} onDiscardCorrupt={async () => { await discardCorruptRecords(); await refresh(); }} />
      {screen === 'P0' ? (
        <ConnectScreen
          blocks={blocks ?? []}
          view={pairingView}
          claim={pairing.claim}
          revoke={pairing.revoke}
          onRetryClaim={pairing.retryClaim}
          onDisconnect={pairing.disconnect}
          onForget={pairing.forget}
          onWipe={async () => {
            await pairing.wipe();
            editorApi.finished();
            setScreen('P0');
          }}
          onNext={() => setScreen('P1')}
          unsentCount={snapshot.entries.filter((entry) => entry.receipt === null && entry.status === 'pending').length}
        />
      ) : null}
      {screen === 'P1' ? (
        <PhotoShapeScreen
          editor={editorApi.editor}
          saveState={editorApi.saveState}
          onChange={editorApi.change}
          onPhoto={(id) => void setPhoto(id)}
          onStartNew={editorApi.startNew}
          onReload={editorApi.reloadFromStorage}
          onNext={() => { void editorApi.flush(); setScreen('P2'); }}
        />
      ) : null}
      {screen === 'P2' ? (
        <DimensionsScreen
          editor={editorApi.editor}
          saveState={editorApi.saveState}
          onChange={editorApi.change}
          onBack={() => setScreen('P1')}
          onReload={editorApi.reloadFromStorage}
          pairingId={activePairing?.pairingId ?? null}
          entries={snapshot.entries}
          confirmed={snapshot.confirmed}
          blocks={blocks ?? []}
          settle={editorApi.settle}
          onEnqueued={async () => {
            editorApi.finished();
            await refresh();
            engine.retryNow();
            setScreen('P3');
          }}
        />
      ) : null}
      {screen === 'P3' ? (
        <OutboxScreen
          entries={snapshot.entries}
          confirmed={snapshot.confirmed}
          view={pairingView}
          online={online}
          running={engine.running}
          transferAllowed={transferAllowed}
          repackable={repackable}
          actions={actions}
          onScanInfo={() => setScreen('P0')}
          onNewItem={() => { editorApi.startNew(); setScreen('P1'); }}
        />
      ) : null}
    </main>
  );
}
