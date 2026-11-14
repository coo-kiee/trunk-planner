// P3: every entry's real state, keeping "received by the relay" and "confirmed by the vehicle" visibly apart.
import type { ConfirmedItem, OutboxEntry } from '../../entities/outbox/model.ts';
import type { PairingView } from '../../entities/pairing/model.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Panel } from '../../shared/ui/Panel.tsx';
import { Status } from '../../shared/ui/Status.tsx';
import styles from '../shell/shell.module.css';
import { OutboxCard } from './OutboxCard.tsx';

export interface OutboxActions {
  retryNow: () => void;
  repackAll: () => Promise<void>;
  resendNewRevision: (entry: OutboxEntry, removePhoto: boolean) => Promise<void>;
  editContinuing: (entry: OutboxEntry) => void;
  editOnAuthoritative: (entry: OutboxEntry) => void;
  editConfirmed: (entry: OutboxEntry, confirmed: ConfirmedItem) => void;
  copyAsNewItem: (entry: OutboxEntry) => void;
  removeLocal: (entry: OutboxEntry) => Promise<void>;
}

interface Props {
  entries: OutboxEntry[];
  confirmed: ConfirmedItem[];
  view: PairingView;
  online: boolean;
  running: boolean;
  transferAllowed: boolean;
  repackable: OutboxEntry[];
  actions: OutboxActions;
  onScanInfo: () => void;
  onNewItem: () => void;
}

export function OutboxScreen({ entries, confirmed, view, online, running, transferAllowed, repackable, actions, onScanInfo, onNewItem }: Props) {
  const sorted = [...entries].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const activePairingId = view.kind === 'ACTIVE' ? view.record.pairingId : null;
  const hasWaiting = entries.some((entry) => ['pending', 'received', 'conflict'].includes(entry.status));

  return (
    <div className={styles.stack}>
      <Panel title="전송함">
        <p className={styles.small}>“서버 수신”은 차량 반영이 아닙니다. 차량 사용자가 수락하고 차량이 확정 버전을 알려 와야 “차량 반영됨”으로 바뀝니다. 앱이 열려 있을 때만 다시 보내고 확인합니다.</p>
        {view.kind !== 'ACTIVE' ? (
          <>
            <Status tone="unverified" icon="⌁" label="차량과 연결되지 않아 보낼 수 없습니다" state="outbox-no-pairing">
              항목은 이 기기에 남아 있습니다. 차량 화면의 새 QR을 스캔하면 옮겨 보낼 수 있습니다.
            </Status>
            <Button onClick={onScanInfo}>연결 안내 보기</Button>
          </>
        ) : null}
        {!transferAllowed ? <Status tone="conflict" icon="!" label="이 환경에서는 전송할 수 없습니다" state="outbox-env-blocked">연결 화면의 안내를 확인하세요. 항목은 이 기기에 남아 있습니다.</Status> : null}
        {repackable.length > 0 ? (
          <div className={styles.tight}>
            <Status tone="unverified" icon="↻" label={`지금 연결로 옮길 수 있는 항목 ${repackable.length}개`} state="repack-offer">
              이전 연결이나 연결 전에 만든 항목입니다. 옮겨 보내도 이전 결과를 지우지 않으며, 차량 수락이 다시 필요합니다. “미확인” 항목은 이미 반영되었을 수 있으니 차량에서 확인하세요.
            </Status>
            <Button variant="primary" onClick={() => void actions.repackAll()}>지금 연결로 옮겨 보내기</Button>
          </div>
        ) : null}
        <div className={styles.row}>
          <Button disabled={!online || !activePairingId || !transferAllowed || running} onClick={actions.retryNow}>{hasWaiting ? '지금 다시 시도' : '상태 새로고침'}</Button>
          <Button onClick={onNewItem}>새 물품 등록</Button>
        </div>
      </Panel>
      {sorted.length === 0 ? <Panel><p className={styles.text}>아직 전송함에 넣은 물품이 없습니다.</p></Panel> : null}
      {sorted.map((entry) => (
        <OutboxCard key={entry.key} entry={entry} confirmed={confirmed} siblings={entries} activePairingId={activePairingId} actions={actions} />
      ))}
    </div>
  );
}
