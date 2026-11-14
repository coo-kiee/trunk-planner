// P1: optional photo, name and shape template. A photo only helps identify the item; photo transfer needs its own consent.
import { nameLength, NAME_MAX, SHAPE_OPTIONS, validateShapeStep, type ItemEditor } from '../../entities/item/model.ts';
import type { SaveState } from '../../features/item-editor/useItemEditor.ts';
import { usePhotoPicker, usePhotoPreview } from '../../features/photo/usePhotoPicker.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Field } from '../../shared/ui/Field.tsx';
import { Panel } from '../../shared/ui/Panel.tsx';
import { Status } from '../../shared/ui/Status.tsx';
import { SaveStatus } from '../shared-save-status.tsx';
import styles from '../shell/shell.module.css';
import { useCallback, useState } from 'react';

interface Props {
  editor: ItemEditor | null;
  saveState: SaveState;
  onChange: <K extends keyof ItemEditor>(field: K, value: ItemEditor[K]) => void;
  /** Replaces or removes the photo and releases the old stored copy. */
  onPhoto: (photoId: string | null) => void;
  onStartNew: () => void;
  onReload: () => void;
  onNext: () => void;
}

export function PhotoShapeScreen({ editor, saveState, onChange, onPhoto, onStartNew, onReload, onNext }: Props) {
  const [showErrors, setShowErrors] = useState(false);
  const onPicked = useCallback((id: string) => onPhoto(id), [onPhoto]);
  const photo = usePhotoPicker(onPicked);
  const preview = usePhotoPreview(editor?.photoId ?? null);

  if (!editor) {
    return (
      <Panel title="새 물품 등록">
        <p className={styles.text}>사진은 선택입니다. 치수는 다음 단계에서 직접 잰 값을 입력합니다.</p>
        <Button variant="primary" onClick={onStartNew}>새 물품 시작</Button>
      </Panel>
    );
  }
  const errors = showErrors ? validateShapeStep(editor) : {};

  return (
    <div className={styles.stack}>
      <Panel title="사진 (선택)">
        <Field kind="file" name="photo" label="물품 사진 고르기" accept="image/*" onSelect={(file) => void photo.pick(file)}
          hint="어떤 물건인지 알아보는 데만 씁니다. 사진으로 치수를 재거나 안전을 판단하지 않습니다." />
        {photo.state.kind === 'WORKING' ? <Status tone="sending" icon="↗" label="사진을 줄이는 중" state="photo-working" live /> : null}
        {photo.state.kind === 'FAILED' ? <Status tone="conflict" icon="!" label="사진을 쓸 수 없습니다" state="photo-failed" live>{photo.state.message}</Status> : null}
        {preview ? (
          <div className={styles.tight}>
            {/* eslint-disable-next-line @next/next/no-img-element -- a local blob preview; there is nothing for an optimizer to fetch */}
            <img className={styles.preview} src={preview.url} alt="고른 물품 사진 미리보기" data-photo-preview />
            <p className={styles.small}>줄인 사진 {preview.info.width}×{preview.info.height}px · {Math.ceil(preview.info.bytes / 1024)}KB · 위치·촬영 정보 제거됨</p>
            <Button onClick={() => onPhoto(null)}>사진 빼기</Button>
          </div>
        ) : null}
        <Field kind="checkbox" name="photoConsent" label="이 사진을 차량으로 보내는 데 동의합니다" checked={editor.photoConsent} disabled={!editor.photoId}
          hint="선택 사항입니다. 동의하지 않으면 사진은 이 기기에만 남고 치수와 취급조건만 보냅니다." onChange={(value) => onChange('photoConsent', value)} />
      </Panel>

      <Panel title="이름과 형상">
        <Field kind="text" name="name" label="물품 이름" value={editor.name} error={errors.name} onChange={(value) => onChange('name', value)}
          hint={`${nameLength(editor.name)}/${NAME_MAX}자. 개인정보를 적지 마세요.`} />
        <Field kind="select" name="shapeKind" label="기본 형상" value={editor.shapeKind} error={errors.shapeKind} placeholder="형상을 고르세요" options={SHAPE_OPTIONS}
          hint={SHAPE_OPTIONS.find((option) => option.value === editor.shapeKind)?.hint ?? '실제 모양을 정밀하게 재현하지 않고 보수적인 상자로 계산합니다.'}
          onChange={(value) => onChange('shapeKind', value as ItemEditor['shapeKind'])} />
        <SaveStatus state={saveState} onReload={onReload} />
        <Button variant="primary" onClick={() => {
          if (Object.keys(validateShapeStep(editor)).length > 0) setShowErrors(true);
          else onNext();
        }}>다음: 치수와 취급조건</Button>
      </Panel>
    </div>
  );
}
