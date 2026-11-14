// Keeps the one in-progress item durable. "Saved" is shown only after the transaction committed.
import { useCallback, useEffect, useRef, useState } from 'react';
import { editItem, newEditor, type ItemEditor } from '../../entities/item/model.ts';
import { clearEditor, EditorVersionConflict, loadSnapshot, saveEditor } from '../../shared/storage/repository.ts';
import { newUuid } from '../../shared/lib/uuid.ts';

export type SaveState = 'IDLE' | 'SAVING' | 'SAVED' | 'FAILED' | 'OTHER_TAB';

const SAVE_DEBOUNCE_MS = 250;

export function useItemEditor(initial: { state: ItemEditor; saveId: string } | null, ready: boolean) {
  const [editor, setEditor] = useState<ItemEditor | null>(initial?.state ?? null);
  const [saveState, setSaveState] = useState<SaveState>(initial ? 'SAVED' : 'IDLE');
  const saveIdRef = useRef<string | null>(initial?.saveId ?? null);
  const savingRef = useRef<Promise<void> | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestRef = useRef<ItemEditor | null>(initial?.state ?? null);
  const dirtyRef = useRef(false);

  useEffect(() => {
    if (!ready || !initial) return;
    saveIdRef.current = initial.saveId;
    latestRef.current = initial.state;
    setEditor(initial.state);
    setSaveState('SAVED');
  }, [ready, initial]);

  /** Saves run one at a time, each from the previous save's id. Returns once everything typed so far has had its save attempt. */
  const flush = useCallback(async () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    while (savingRef.current) await savingRef.current;
    const state = latestRef.current;
    if (!state || !dirtyRef.current) return;
    dirtyRef.current = false;
    setSaveState('SAVING');
    const saving = (async () => {
      try {
        saveIdRef.current = await saveEditor(state, saveIdRef.current);
        // Only claim "saved" if nothing newer was typed while this transaction ran.
        if (!dirtyRef.current) setSaveState('SAVED');
      } catch (cause) {
        dirtyRef.current = true;
        setSaveState(cause instanceof EditorVersionConflict ? 'OTHER_TAB' : 'FAILED');
      }
    })();
    savingRef.current = saving;
    await saving;
    savingRef.current = null;
  }, []);

  /** Saves what is on screen, then reports which stored editor this tab stands on, for the enqueue compare-and-set. */
  const settle = useCallback(async () => {
    await flush();
    return saveIdRef.current;
  }, [flush]);

  const replace = useCallback((state: ItemEditor) => {
    latestRef.current = state;
    dirtyRef.current = true;
    setEditor(state);
    setSaveState('SAVING');
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => void flush(), SAVE_DEBOUNCE_MS);
  }, [flush]);

  const change = useCallback(<K extends keyof ItemEditor>(field: K, value: ItemEditor[K]) => {
    const base = latestRef.current ?? newEditor(newUuid(), new Date().toISOString());
    replace(editItem(base, field, value));
  }, [replace]);

  /** Starts a blank item. Dimensions are empty on purpose: nothing is pre-filled. */
  const startNew = useCallback(() => {
    replace(newEditor(newUuid(), new Date().toISOString()));
  }, [replace]);

  /** Local state reset after the outbox transaction already removed the stored editor. */
  const finished = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    dirtyRef.current = false;
    latestRef.current = null;
    saveIdRef.current = null;
    setEditor(null);
    setSaveState('IDLE');
  }, []);

  const discard = useCallback(async () => {
    finished();
    await clearEditor().catch(() => undefined);
  }, [finished]);

  /** After another tab saved: take its version instead of silently overwriting either side. */
  const reloadFromStorage = useCallback(async () => {
    const snapshot = await loadSnapshot();
    saveIdRef.current = snapshot.editor?.saveId ?? null;
    latestRef.current = snapshot.editor?.state ?? null;
    dirtyRef.current = false;
    setEditor(snapshot.editor?.state ?? null);
    setSaveState(snapshot.editor ? 'SAVED' : 'IDLE');
  }, []);

  // Leaving the page should not lose the debounce window.
  useEffect(() => {
    const onHide = () => void flush();
    window.addEventListener('pagehide', onHide);
    document.addEventListener('visibilitychange', onHide);
    return () => {
      window.removeEventListener('pagehide', onHide);
      document.removeEventListener('visibilitychange', onHide);
    };
  }, [flush]);

  return { editor, saveState, change, replace, startNew, finished, discard, flush, settle, reloadFromStorage };
}
