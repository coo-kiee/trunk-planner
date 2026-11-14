// P0: connect by QR, gate on the environment, explain expiry, disconnection and privacy.
import { useState } from 'react';
import type { PairingRecord, PairingView } from '../../entities/pairing/model.ts';
import type { ClaimState } from '../../features/pairing/usePairing.ts';
import { ENVIRONMENT_MESSAGES, type EnvironmentBlock } from '../../shared/security/environment.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Panel } from '../../shared/ui/Panel.tsx';
import { Status } from '../../shared/ui/Status.tsx';
import { TextLink } from '../../shared/ui/TextLink.tsx';
import styles from '../shell/shell.module.css';
import { claimFailureMessage } from './claim-messages.ts';

interface Props {
  blocks: EnvironmentBlock[];
  view: PairingView;
  claim: ClaimState;
  revoke: { kind: 'IDLE' | 'WORKING' | 'DONE' } | { kind: 'FAILED'; code: string };
  unsentCount: number;
  onRetryClaim: () => void;
  onDisconnect: (record: PairingRecord, options: { localOnly: boolean }) => void;
  onForget: () => void;
  onWipe: () => void;
  onNext: () => void;
}

const formatTime = (iso: string) => new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' });

export function ConnectScreen({ blocks, view, claim, revoke, unsentCount, onRetryClaim, onDisconnect, onForget, onWipe, onNext }: Props) {
  const [confirmWipe, setConfirmWipe] = useState(false);
  const transferBlocked = blocks.length > 0;
  return (
    <div className={styles.stack}>
      {blocks.map((block) => (
        <Status key={block} tone="conflict" icon="!" label={ENVIRONMENT_MESSAGES[block].title} state={`env-${block}`}>
          {ENVIRONMENT_MESSAGES[block].recovery}
        </Status>
      ))}

      <Panel title="차량과 연결">
        {claim.kind === 'CLAIMING' ? <Status tone="sending" icon="↗" label="차량과 연결하는 중" state="claiming" live /> : null}
        {claim.kind === 'FAILED' ? (
          <Status tone="conflict" icon="!" label="연결하지 못했습니다" state={`claim-${claim.code}`} live>
            {claimFailureMessage(claim.code)}
          </Status>
        ) : null}
        {claim.kind === 'FAILED' && claim.retryable ? <Button variant="primary" onClick={onRetryClaim}>다시 연결</Button> : null}

        {view.kind === 'ACTIVE' ? (
          <Status tone="accepted" icon="✓" label="차량과 연결됨" state="paired">
            {formatTime(view.record.credentialExpiresAt)}까지 유효합니다. 연결되었다고 물품이 차량에 반영된 것은 아닙니다.
          </Status>
        ) : null}
        {view.kind === 'EXPIRED' ? (
          <Status tone="stale" icon="◷" label="연결이 만료되었습니다" state="pairing-expired">
            차량 화면에서 새 QR을 만들어 스캔하세요. 보내지 못한 항목은 이 기기에 남아 있습니다.
          </Status>
        ) : null}
        {view.kind === 'REVOKED' ? (
          <Status tone="stale" icon="⊘" label="연결이 해제되었습니다" state="pairing-revoked">
            차량 화면에서 새 QR을 만들어 스캔하면 다시 연결됩니다.
          </Status>
        ) : null}
        {view.kind === 'NONE' && claim.kind !== 'CLAIMING' && claim.kind !== 'FAILED' ? (
          <Status tone="unverified" icon="⌁" label="아직 연결되지 않았습니다" state="not-paired">
            차량 앱의 “휴대폰 연결” 화면에 나오는 QR을 휴대폰 카메라로 스캔하세요. QR은 5분 동안, 한 번만 쓸 수 있습니다.
          </Status>
        ) : null}

        <div className={styles.row}>
          <Button variant="primary" onClick={onNext}>{view.kind === 'ACTIVE' ? '물품 등록하기' : '연결 없이 먼저 작성하기'}</Button>
        </div>
        {transferBlocked ? <p className={styles.small}>위 문제가 해결되기 전에는 연결과 전송을 막습니다. 작성 내용은 이 기기에만 저장됩니다.</p> : null}
      </Panel>

      {view.kind === 'ACTIVE' ? (
        <Panel title="연결 해제" level={3}>
          <p className={styles.text}>
            해제하면 전송 서버에 있는 미처리 내용이 지워지고, 차량의 결과를 더 읽을 수 없습니다. 이 기기의 보내지 못한 항목 {unsentCount}개는 남겨 둡니다.
          </p>
          <Button variant="danger" disabled={revoke.kind === 'WORKING'} onClick={() => onDisconnect(view.record, { localOnly: false })}>연결 해제</Button>
          {revoke.kind === 'FAILED' ? (
            <>
              <Status tone="conflict" icon="!" label="서버에 해제 요청을 전하지 못했습니다" state="revoke-failed" live>
                서버 쪽 연결은 아직 살아 있을 수 있습니다. 차량에서도 해제할 수 있고, 그대로 두면 최대 24시간 뒤 만료됩니다.
              </Status>
              <Button variant="danger" onClick={() => onDisconnect(view.record, { localOnly: true })}>이 기기에서만 연결 정보 지우기</Button>
            </>
          ) : null}
        </Panel>
      ) : null}
      {view.kind === 'EXPIRED' || view.kind === 'REVOKED' ? (
        <Panel level={3} title="연결 기록"><Button onClick={onForget}>이 연결 기록 지우기</Button></Panel>
      ) : null}

      <Panel title="개인정보 안내" level={3}>
        <p className={styles.text}>기본으로 치수와 취급조건만 보냅니다. 사진은 따로 동의할 때만 보냅니다. 종단간 암호화는 아니며, 전송 서버에 잠시 보관됩니다.</p>
        <TextLink href="/privacy">전체 안내 보기</TextLink>
      </Panel>

      <Panel title="이 기기의 데이터" level={3}>
        <p className={styles.text}>이 기기에 저장된 작성 내용, 사진, 전송함, 연결 정보를 지웁니다. 이미 차량이나 전송 서버에 있는 내용은 지우지 못합니다.</p>
        {confirmWipe ? (
          <div className={styles.row}>
            <Button variant="danger" onClick={() => { setConfirmWipe(false); onWipe(); }}>정말 모두 지우기</Button>
            <Button onClick={() => setConfirmWipe(false)}>취소</Button>
          </div>
        ) : (
          <Button variant="danger" onClick={() => setConfirmWipe(true)}>이 기기의 데이터 모두 지우기</Button>
        )}
      </Panel>
    </div>
  );
}
