// One outbox entry. Three numbers are never merged: the sent edit number, the base it was written against, the vehicle-confirmed revision.
import { useState, type ReactNode } from 'react';
import type { ConfirmedItem, OutboxEntry } from '../../entities/outbox/model.ts';
import { failureGuidance, REJECT_LABELS, STATUS_LABELS } from '../../entities/outbox/presentation.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Panel } from '../../shared/ui/Panel.tsx';
import { Status } from '../../shared/ui/Status.tsx';
import styles from '../shell/shell.module.css';
import type { OutboxActions } from './OutboxScreen.tsx';

interface Props {
  entry: OutboxEntry;
  confirmed: ConfirmedItem[];
  siblings: OutboxEntry[];
  activePairingId: string | null;
  actions: OutboxActions;
}

function Fact({ name, children, id }: { name: string; children: ReactNode; id: string }) {
  return (
    <div className={styles.fact} data-fact={id}>
      <dt className={styles.factName}>{name}</dt>
      <dd className={styles.factValue}>{children}</dd>
    </div>
  );
}

export function OutboxCard({ entry, confirmed, siblings, activePairingId, actions }: Props) {
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { draft } = entry;
  const payload = draft.payload;
  const presentation = STATUS_LABELS[entry.status];
  const ack = entry.terminalAck;
  const baseId = draft.baseItemId ?? payload.id;
  const vehicleKnown = confirmed.find((item) => item.acceptedItemId === baseId);
  const hasNewerLocal = siblings.some((other) => other.draft.draftId === draft.draftId && other.draft.clientRevision > draft.clientRevision);
  const guidance = entry.lastFailure ? failureGuidance(entry.lastFailure.code) : null;
  const size = payload.measuredDimensionsMm;

  return (
    <Panel title={payload.name} level={3} testId={`entry-${entry.key}`}>
      <Status tone={presentation.tone} icon={presentation.icon} label={presentation.label} state={entry.status}>{presentation.meaning}</Status>

      <dl className={styles.facts}>
        <Fact id="size" name="실측 치수">{size.x} × {size.y} × {size.z} mm (오차 ±{payload.errorMm} mm)</Fact>
        <Fact id="client-revision" name="이 폰의 수정 번호(차량 확정 아님)">{draft.clientRevision}</Fact>
        {draft.baseItemRevision !== undefined ? <Fact id="base-revision" name="작성 기준 차량 revision">{draft.baseItemRevision}</Fact> : null}
        <Fact id="vehicle-revision" name="차량 확정 revision">
          {ack?.result === 'VEHICLE_ACCEPTED' ? `${ack.acceptedRevision} (이 수정본 반영)` : vehicleKnown ? `${vehicleKnown.acceptedRevision} (이전에 확인한 값, 이 수정본은 아직 반영 전)` : '아직 없음'}
        </Fact>
        <Fact id="photo" name="사진">{entry.consent.photoConsent ? '함께 보냄(동의함)' : '보내지 않음'}</Fact>
      </dl>

      {ack?.result === 'VEHICLE_ACCEPTED' && ack.acceptedItemId !== payload.id ? (
        <Status tone="accepted" icon="✓" label="차량이 별도 물품으로 저장했습니다" state="accepted-separate">앞으로 이 물품을 고치면 차량의 실제 물품 기준으로 보냅니다.</Status>
      ) : null}
      {ack?.result === 'VEHICLE_REJECTED' && ack.rejectCode ? <p className={styles.text} data-reject-code={ack.rejectCode}>{REJECT_LABELS[ack.rejectCode]}</p> : null}
      {entry.conflict && !ack ? (
        <Status tone="conflict" icon="⚠" label={`차량 현재 revision ${entry.conflict.authoritativeRevision} · 이 수정본의 기준 ${draft.baseItemRevision ?? '없음(새 물품)'}`} state="conflict-detail">
          기존 유지, 새 버전 적용, 별도 물품 중 선택은 차량 화면에서 합니다. 이 화면은 차량이 실제로 알려 온 결과만 표시합니다.
        </Status>
      ) : null}
      {entry.resentAfterUnknown ? <p className={styles.small} data-resent-unknown>이전 연결에서 결과를 확인하지 못한 항목을 다시 보냈습니다. 차량에 이미 반영되어 있을 수 있습니다.</p> : null}
      {entry.replacedBy !== null && entry.status !== 'superseded' ? <p className={styles.small} data-replaced-by>새 수정본(수정 번호 {entry.replacedBy})을 만들었습니다. 차량이 이 수정본을 먼저 처리했다면 그 결과가 여기에 표시됩니다.</p> : null}
      {guidance && !ack ? (
        <p className={styles.text} data-failure-code={entry.lastFailure?.code}>
          {/* Already connected again: asking for another QR would be wrong, the offer above is the next step. */}
          {guidance.recovery === 'NEW_QR' && activePairingId ? '이전 연결이 끝난 항목입니다. 위의 “지금 연결로 옮겨 보내기”로 옮길 수 있습니다.' : guidance.message}
        </p>
      ) : null}
      {entry.status === 'pending' && !entry.nextAttemptAt && entry.lastFailure ? <p className={styles.small} data-auto-stopped>자동 재시도를 멈췄습니다. “지금 다시 시도”를 누르거나 앱을 다시 열면 재개합니다.</p> : null}
      {entry.status === 'pending' && entry.draft.pairingId !== activePairingId ? <p className={styles.small} data-awaits-pairing>차량과 연결한 뒤 “지금 연결로 옮겨 보내기”를 누르면 보냅니다.</p> : null}

      <div className={styles.row}>
        {entry.status === 'conflict' && !hasNewerLocal ? <Button onClick={() => actions.editOnAuthoritative(entry)}>차량 revision {entry.conflict?.authoritativeRevision} 기준으로 고쳐 다시 보내기</Button> : null}
        {entry.status === 'conflict' || entry.status === 'rejected' ? <Button onClick={() => actions.copyAsNewItem(entry)}>별도 물품으로 새로 등록</Button> : null}
        {ack?.result === 'VEHICLE_ACCEPTED' && !hasNewerLocal ? <AcceptedEdit entry={entry} confirmed={confirmed} actions={actions} /> : null}
        {['pending', 'received'].includes(entry.status) && !hasNewerLocal ? <Button onClick={() => actions.editContinuing(entry)}>내용 고쳐 새 수정본 보내기</Button> : null}
        {entry.status === 'blocked' && !hasNewerLocal && guidance?.recovery === 'NEW_REVISION' ? <Button variant="primary" onClick={() => void actions.resendNewRevision(entry, false)}>새 수정 번호로 다시 보내기</Button> : null}
        {entry.status === 'blocked' && !hasNewerLocal && guidance?.recovery === 'EDIT' ? <Button variant="primary" onClick={() => actions.editContinuing(entry)}>내용 열어 고치기</Button> : null}
        {(entry.status === 'blocked' && guidance?.recovery === 'REMOVE_PHOTO') || (entry.status === 'pending' && entry.consent.photoConsent && !hasNewerLocal) ? <Button onClick={() => void actions.resendNewRevision(entry, true)}>사진 빼고 다시 보내기</Button> : null}
        {entry.status === 'blocked' && guidance?.recovery === 'RETRY' ? <Button onClick={actions.retryNow}>다시 시도</Button> : null}
        {canRemove(entry) ? (
          confirmDelete ? (
            <>
              <Button variant="danger" onClick={() => void actions.removeLocal(entry)}>이 기기에서 지우기 확인</Button>
              <Button onClick={() => setConfirmDelete(false)}>취소</Button>
            </>
          ) : <Button variant="danger" onClick={() => setConfirmDelete(true)}>이 기기에서 지우기</Button>
        ) : null}
      </div>
    </Panel>
  );
}

/** A received entry cannot be recalled from the relay, so it can't be removed until an answer exists. */
function canRemove(entry: OutboxEntry): boolean {
  return entry.status !== 'received' && entry.status !== 'conflict' && entry.status !== 'sending';
}

function AcceptedEdit({ entry, confirmed, actions }: Pick<Props, 'entry' | 'confirmed' | 'actions'>) {
  const known = confirmed.find((item) => item.draftKey === entry.key) ?? confirmed.find((item) => item.acceptedItemId === entry.terminalAck?.acceptedItemId);
  if (!known) return null;
  return <Button onClick={() => actions.editConfirmed(entry, known)}>이 물품 고쳐서 다시 보내기</Button>;
}
