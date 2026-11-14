// Storage and network health across every screen. Storage faults stop writes but never delete anything.
import { useState } from 'react';
import type { StorageFailure } from '../../shared/storage/idb.ts';
import { Button } from '../../shared/ui/Button.tsx';
import { Status } from '../../shared/ui/Status.tsx';

export type StorageHealth = 'OK' | 'CORRUPT' | StorageFailure;

const MESSAGES: Record<Exclude<StorageHealth, 'OK'>, { label: string; detail: string }> = {
  CORRUPT: { label: '읽을 수 없는 저장 기록이 있습니다', detail: '손상된 기록은 지우지 않고 따로 보관하며, 화면에 쓰거나 전송하지 않습니다. 나머지 항목은 평소처럼 쓸 수 있습니다. 필요한 물품은 새로 등록하고, 연결이 풀렸다면 차량 화면의 새 QR을 스캔하세요.' },
  FUTURE_VERSION: { label: '더 새로운 앱이 만든 저장 형식입니다', detail: '데이터를 보존하기 위해 읽기·쓰기를 멈췄습니다. 앱을 최신 버전으로 열어 주세요. 저장소를 지우지 마세요.' },
  QUOTA: { label: '저장 공간이 부족해 저장하지 못했습니다', detail: '기기 저장 공간을 확보한 뒤 다시 시도하세요. 방금 입력한 내용은 저장되지 않았을 수 있습니다.' },
  WRITE_FAILED: { label: '저장에 실패했습니다', detail: '방금 입력한 내용은 저장되지 않았을 수 있습니다. 앱을 닫지 말고 다시 시도하세요.' },
  UNAVAILABLE: { label: '이 브라우저 저장소를 열 수 없습니다', detail: '시크릿 모드나 저장 차단 설정을 끄고 다시 여세요. 저장되지 않으면 작성 내용을 잃을 수 있습니다.' },
  BLOCKED: { label: '다른 탭이 저장소를 사용 중입니다', detail: '이 앱의 다른 탭을 닫고 새로고침하세요.' },
};

interface Props {
  health: StorageHealth;
  corruptCount: number;
  online: boolean;
  onDiscardCorrupt: () => void;
}

export function StorageBanner({ health, corruptCount, online, onDiscardCorrupt }: Props) {
  const [confirming, setConfirming] = useState(false);
  return (
    <>
      {health !== 'OK' ? (
        <Status tone="conflict" icon="!" label={MESSAGES[health].label} state={`storage-${health}`} live>
          {MESSAGES[health].detail}{health === 'CORRUPT' ? ` (읽지 못한 기록 ${corruptCount}개)` : ''}
        </Status>
      ) : null}
      {health === 'CORRUPT' ? (
        confirming ? (
          <>
            <Button variant="danger" onClick={() => { setConfirming(false); onDiscardCorrupt(); }}>손상 기록 지우기 확인</Button>
            <Button onClick={() => setConfirming(false)}>취소</Button>
          </>
        ) : <Button onClick={() => setConfirming(true)}>읽을 수 없는 기록만 지우기</Button>
      ) : null}
      {!online ? (
        <Status tone="unverified" icon="⌁" label="오프라인입니다" state="offline" live>
          작성과 저장은 계속할 수 있습니다. 보내기는 연결이 돌아오면 앱이 열려 있을 때 다시 시도합니다.
        </Status>
      ) : null}
    </>
  );
}
