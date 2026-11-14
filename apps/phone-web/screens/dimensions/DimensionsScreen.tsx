// P2: the user's own measurements and handling conditions. Nothing is pre-filled, and only explicit confirmation can send.
import { useState } from 'react';
import { ROTATION_OPTIONS, SOURCE_OPTIONS, validateForEnqueue, type EditorErrors, type ItemEditor } from '../../entities/item/model.ts';
import type { ConfirmedItem, OutboxEntry } from '../../entities/outbox/model.ts';
import type { SaveState } from '../../features/item-editor/useItemEditor.ts';
import { enqueueFromEditor } from '../../features/outbox/enqueue.ts';
import type { EnvironmentBlock } from '../../shared/security/environment.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Field } from '../../shared/ui/Field.tsx';
import { Panel } from '../../shared/ui/Panel.tsx';
import { Status } from '../../shared/ui/Status.tsx';
import { SaveStatus } from '../shared-save-status.tsx';
import styles from '../shell/shell.module.css';

interface Props {
  editor: ItemEditor | null;
  saveState: SaveState;
  onChange: <K extends keyof ItemEditor>(field: K, value: ItemEditor[K]) => void;
  onBack: () => void;
  onReload: () => void;
  pairingId: string | null;
  entries: OutboxEntry[];
  confirmed: ConfirmedItem[];
  blocks: EnvironmentBlock[];
  /** Saves pending input and returns the id of the stored editor this tab stands on. */
  settle: () => Promise<string | null>;
  onEnqueued: () => void;
}

const ENQUEUE_FAILURES: Record<string, string> = {
  SCHEMA: '입력값이 공통 계약에 맞지 않아 전송함에 넣지 않았습니다. 값을 확인하세요.',
  PHOTO_MISSING: '동의한 사진을 찾을 수 없습니다. 사진을 다시 고르거나 사진 동의를 해제하세요.',
  STORAGE: '이 기기에 저장하지 못해 전송함에 넣지 않았습니다. 저장 공간을 확인하세요.',
  EDITOR_CONFLICT: '다른 탭에서 이 초안을 먼저 고쳤거나 전송함에 넣었습니다. 전송함에 넣지 않았고 아무것도 지우지 않았습니다. 다른 탭의 최신 내용을 불러와 확인한 뒤 다시 시도하세요.',
  REVISION_RACE: '다른 탭에서 먼저 새 수정본을 만들었습니다. 전송함을 확인한 뒤 다시 시도하세요.',
};

export function DimensionsScreen({ editor, saveState, onChange, onBack, onReload, pairingId, entries, confirmed, blocks, settle, onEnqueued }: Props) {
  const [errors, setErrors] = useState<EditorErrors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editorConflict, setEditorConflict] = useState(false);

  if (!editor) {
    return (
      <Panel title="치수와 취급조건">
        <Status tone="unverified" icon="⌁" label="작성 중인 물품이 없습니다" state="no-editor">먼저 사진·형상 단계에서 새 물품을 시작하세요.</Status>
        <Button variant="primary" onClick={onBack}>사진·형상 단계로</Button>
      </Panel>
    );
  }

  const set = <K extends keyof ItemEditor>(field: K) => (value: ItemEditor[K]) => {
    onChange(field, value);
    setErrors({});
    setFailure(null);
    setEditorConflict(false); // Typing starts a new save, whose own status reports the other tab's change.
  };

  const submit = async () => {
    const found = validateForEnqueue(editor);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) return;
    setBusy(true);
    const result = await enqueueFromEditor(editor, pairingId, entries, await settle());
    setBusy(false);
    if (result.ok) onEnqueued();
    else if (result.reason === 'INVALID') setErrors(result.errors);
    else {
      setFailure(ENQUEUE_FAILURES[result.reason] ?? result.reason);
      setEditorConflict(result.reason === 'EDITOR_CONFLICT');
    }
  };

  const known = editor.base ? confirmed.find((item) => item.acceptedItemId === editor.base?.itemId) : undefined;

  return (
    <div className={styles.stack}>
      {editor.base ? (
        <Status tone="unverified" icon="⌁" label={`차량 확정 revision ${editor.base.revision} 기준으로 수정 중`} state="editing-base">
          이 폰이 마지막으로 확인한 차량 버전{known ? `(revision ${known.acceptedRevision})` : ''}입니다. 차량에서 그 뒤에 바뀌었다면, 보낸 뒤 차량에서 어느 쪽을 쓸지 고르게 됩니다.
        </Status>
      ) : null}
      <Panel title="실측 치수">
        <p className={styles.small}>줄자로 잰 실제 치수를 mm로 입력하세요. 사진에서 치수를 가져오지 않습니다.</p>
        <div className={styles.triple}>
          <Field kind="integer" name="dimX" label="폭 x (mm)" value={editor.dimX} error={errors.dimX} onChange={set('dimX')} />
          <Field kind="integer" name="dimY" label="높이 y (mm)" value={editor.dimY} error={errors.dimY} onChange={set('dimY')} />
          <Field kind="integer" name="dimZ" label="깊이 z (mm)" value={editor.dimZ} error={errors.dimZ} onChange={set('dimZ')} />
        </div>
        {editor.shapeKind === 'L_SHAPE' ? (
          <div className={styles.triple}>
            <Field kind="integer" name="legX" label="기둥 폭 (mm)" value={editor.legX} error={errors.legX} onChange={set('legX')} hint="전체 깊이만큼 뻗은 부분의 폭" />
            <Field kind="integer" name="footZ" label="발 깊이 (mm)" value={editor.footZ} error={errors.footZ} onChange={set('footZ')} hint="옆으로 뻗은 부분의 깊이" />
          </div>
        ) : null}
        <Field kind="integer" name="errorMm" label="측정 오차 ± (mm)" value={editor.errorMm} error={errors.errorMm} onChange={set('errorMm')} hint="재면서 틀릴 수 있는 정도입니다. 차량 판정에 그대로 반영됩니다." />
        <Field kind="select" name="measurementSource" label="측정 출처" value={editor.measurementSource} error={errors.measurementSource} placeholder="출처를 고르세요" options={SOURCE_OPTIONS}
          hint="측정앱 연동은 아직 없습니다(추후 과제)." onChange={(value) => set('measurementSource')(value as ItemEditor['measurementSource'])} />
      </Panel>

      <Panel title="취급조건">
        <Field kind="select" name="rotationRule" label="회전·세움" value={editor.rotationRule} error={errors.rotationRule} placeholder="조건을 고르세요" options={ROTATION_OPTIONS}
          onChange={(value) => set('rotationRule')(value as ItemEditor['rotationRule'])} />
        <Field kind="select" name="stackable" label="위에 다른 짐 올리기" value={editor.stackable === null ? '' : String(editor.stackable)} error={errors.stackable} placeholder="가능 여부를 고르세요"
          options={[{ value: 'true', label: '올려도 됨' }, { value: 'false', label: '올리면 안 됨' }]}
          onChange={(value) => set('stackable')(value === '' ? null : value === 'true')} />
        <Field kind="integer" name="removalPriority" label="꺼냄 우선순위 (0~100)" value={editor.removalPriority} error={errors.removalPriority} onChange={set('removalPriority')} hint="숫자가 작을수록 먼저 꺼냅니다." />
      </Panel>

      <Panel title="확정과 동의">
        <Field kind="checkbox" name="confirmedDimensions" label="위 치수는 제가 직접 확인한 실제 값입니다" checked={editor.confirmedDimensions} error={errors.confirmedDimensions}
          hint="치수·형상·오차·출처를 바꾸면 이 확정은 자동으로 풀립니다. 확정 전에는 보내거나 배치할 수 없습니다." onChange={set('confirmedDimensions')} />
        <Field kind="checkbox" name="dimensionsConsent" label="치수와 취급조건을 전송 서버를 거쳐 차량으로 보내는 데 동의합니다" checked={editor.dimensionsConsent} error={errors.dimensionsConsent} onChange={set('dimensionsConsent')} />
        <p className={styles.small}>사진 전송: {editor.photoId ? (editor.photoConsent ? '동의함(사진도 함께 보냄)' : '동의 안 함(사진은 이 기기에만 남음)') : '사진 없음'}</p>
        {blocks.length > 0 ? <Status tone="unverified" icon="⌁" label="지금 환경에서는 전송되지 않습니다" state="env-blocked-note">전송함에는 넣을 수 있지만, 보안 연결 문제가 해결될 때까지 이 기기에서 대기합니다.</Status> : null}
        {!pairingId ? <Status tone="unverified" icon="⌁" label="차량과 연결되지 않았습니다" state="no-pairing-note">전송함에 넣어 두고, 차량 QR을 스캔한 뒤 보낼 수 있습니다.</Status> : null}
        {failure ? <Status tone="conflict" icon="!" label="전송함에 넣지 못했습니다" state="enqueue-failed" live>{failure}</Status> : null}
        {editorConflict ? <Button variant="primary" onClick={() => { setFailure(null); setEditorConflict(false); onReload(); }}>다른 탭의 최신 내용 불러오기</Button> : null}
        {/* This tab's copy is out of date, so "saved" would be untrue until the latest version is loaded. */}
        {editorConflict ? null : <SaveStatus state={saveState} onReload={onReload} />}
        <div className={styles.row}>
          <Button onClick={onBack}>이전</Button>
          <Button variant="primary" disabled={busy || !editor.confirmedDimensions || !editor.dimensionsConsent} onClick={() => void submit()}>전송함에 넣기</Button>
        </div>
      </Panel>
    </div>
  );
}
