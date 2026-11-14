import type { SaveState } from '../features/item-editor/useItemEditor.ts';
import { Button } from '../shared/ui/Button.tsx';
import { Status } from '../shared/ui/Status.tsx';

/** Saved is claimed only after a committed transaction. Another tab's edit is offered, never overwritten. */
export function SaveStatus({ state, onReload }: { state: SaveState; onReload: () => void }) {
  if (state === 'IDLE') return null;
  if (state === 'SAVING') return <Status tone="pending" icon="…" label="저장하는 중" state="save-saving" live />;
  if (state === 'SAVED') return <Status tone="clear" icon="✓" label="이 기기에 저장됨" state="save-saved" live>차량으로 보낸 것은 아닙니다.</Status>;
  if (state === 'OTHER_TAB') {
    return (
      <Status tone="conflict" icon="⚠" label="다른 탭에서 먼저 수정했습니다" state="save-other-tab" live>
        이 탭의 입력은 저장되지 않았습니다.
        <Button onClick={onReload}>다른 탭의 내용 불러오기</Button>
      </Status>
    );
  }
  return <Status tone="conflict" icon="!" label="저장하지 못했습니다" state="save-failed" live>이 입력은 아직 기기에 저장되지 않았습니다. 앱을 닫지 말고 저장 공간을 확인하세요.</Status>;
}
