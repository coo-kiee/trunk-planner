// One place that maps every outbox state to its words, icon and design tone, so colour is never the only signal.
import type { StatusTone } from '../../shared/ui/status-tones.ts';
import type { OutboxEntry, OutboxStatus } from './model.ts';

export const STATUS_LABELS: Record<OutboxStatus, { label: string; icon: string; tone: StatusTone; meaning: string }> = {
  pending: { label: '이 폰에서 대기', icon: '…', tone: 'pending', meaning: '아직 전송 서버에 도착하지 않았습니다. 이 기기에 저장되어 있습니다.' },
  sending: { label: '보내는 중', icon: '↗', tone: 'sending', meaning: '전송 서버로 보내고 있습니다.' },
  received: { label: '서버 수신 · 차량 반영 아님', icon: '✉', tone: 'received', meaning: '전송 서버가 받았습니다. 차량 사용자가 수락하기 전에는 차량에 반영되지 않습니다.' },
  conflict: { label: '충돌 · 차량에서 선택 필요', icon: '⚠', tone: 'conflict', meaning: '같은 물품이 차량에서도 바뀌었습니다. 조용히 덮어쓰지 않으며, 차량 화면에서 어느 쪽을 쓸지 고릅니다.' },
  accepted: { label: '차량 반영됨', icon: '✓', tone: 'accepted', meaning: '차량 사용자가 수락했고 차량이 확정 버전을 알려 왔습니다.' },
  rejected: { label: '차량이 받지 않음', icon: '✕', tone: 'stale', meaning: '차량이 이 수정본을 반영하지 않기로 했습니다.' },
  superseded: { label: '새 수정본으로 교체됨', icon: '↻', tone: 'stale', meaning: '더 새로운 수정본이 있어 이 수정본은 더 이상 쓰이지 않습니다.' },
  expired: { label: '만료됨', icon: '◷', tone: 'stale', meaning: '보관 기간이 지났거나 연결이 만료되었습니다. 차량에 반영되지 않은 상태로 끝났습니다.' },
  revoked: { label: '연결 해제됨', icon: '⊘', tone: 'stale', meaning: '연결이 해제되어 보낼 수 없습니다. 내용은 이 기기에 남아 있습니다.' },
  unknown: { label: '차량 반영 여부 미확인', icon: '?', tone: 'unverified', meaning: '연결이 끊어져 차량의 결과를 읽을 수 없습니다. 반영되었는지 안 되었는지 알 수 없으니, 차량 화면에서 확인하세요.' },
  blocked: { label: '멈춤 · 확인 필요', icon: '!', tone: 'conflict', meaning: '자동으로 다시 보내도 해결되지 않는 문제입니다. 아래 안내를 확인하세요.' },
};

export const REJECT_LABELS: Record<NonNullable<NonNullable<OutboxEntry['terminalAck']>['rejectCode']>, string> = {
  USER_DISCARDED: '차량 사용자가 이 수정본을 버렸습니다.',
  USER_KEPT_VEHICLE_VERSION: '차량 사용자가 차량에 있던 버전을 유지하기로 했습니다.',
  VALIDATION_FAILED: '차량이 내용을 검증하지 못해 받지 않았습니다.',
};

/** Exact recovery guidance per typed relay code. Unlisted codes still get a truthful, generic line. */
export function failureGuidance(code: string): { message: string; recovery: 'NEW_REVISION' | 'REMOVE_PHOTO' | 'NEW_QR' | 'REPACK' | 'EDIT' | 'RETRY' | 'NONE' } {
  switch (code) {
    case 'CONNECTION_FAILED': return { message: '전송 서버에 연결하지 못했습니다(네트워크 또는 설정 문제). 앱이 열려 있는 동안 다시 시도합니다.', recovery: 'RETRY' };
    case 'RATE_LIMITED': return { message: '요청이 너무 많아 잠시 후 다시 시도합니다.', recovery: 'RETRY' };
    case 'DATABASE_UNAVAILABLE':
    case 'INTERNAL': return { message: '전송 서버에 일시적인 문제가 있습니다. 앱이 열려 있는 동안 다시 시도합니다.', recovery: 'RETRY' };
    case 'DRAFT_CONTENT_CONFLICT': return { message: '같은 수정 번호로 다른 내용이 이미 전송되어 있습니다. 새 수정 번호로 보내야 합니다.', recovery: 'NEW_REVISION' };
    case 'STALE_CLIENT_REVISION': return { message: '이 물품의 더 새로운 수정본이 이미 전송되었습니다(다른 탭일 수 있습니다). 최신 번호로 다시 보낼 수 있습니다.', recovery: 'NEW_REVISION' };
    case 'PAIRING_MISMATCH': return { message: '다른 차량 연결의 항목입니다. 지금 연결로 옮겨야 보낼 수 있습니다.', recovery: 'REPACK' };
    case 'CONSENT_VERSION_UNSUPPORTED':
    case 'CONSENT_REQUIRED':
    case 'CONSENT_INVALID': return { message: '동의 내용이 현재 전송 서버와 맞지 않습니다. 내용을 열어 다시 동의한 뒤 보내세요. 그래도 멈추면 앱을 새로 열거나 차량 화면의 새 QR로 다시 연결하세요. 자동으로 다시 시도하지 않습니다.', recovery: 'EDIT' };
    case 'PHOTO_TOO_LARGE':
    case 'PHOTO_DIMENSIONS_TOO_LARGE':
    case 'PHOTO_METADATA_FORBIDDEN':
    case 'PHOTO_MALFORMED':
    case 'PHOTO_TYPE_FORBIDDEN':
    case 'PHOTO_REF_INVALID':
    case 'PHOTO_CONSENT_REQUIRED': return { message: '전송 서버가 사진을 받지 않았습니다. 사진을 빼고 치수만 보낼 수 있습니다.', recovery: 'REMOVE_PHOTO' };
    case 'PAIRING_DRAFT_LIMIT':
    case 'PAIRING_BYTES_LIMIT':
    case 'ACTIVE_PAIRING_LIMIT': return { message: '이 연결로 보관할 수 있는 양을 넘었습니다. 차량에서 먼저 받은 뒤 다시 시도하세요.', recovery: 'RETRY' };
    case 'DRAFT_SCHEMA_INVALID':
    case 'DRAFT_BASE_INCOMPLETE':
    case 'BODY_TOO_LARGE': return { message: '전송 서버가 내용 형식을 받지 않았습니다. 내용을 열어 고친 뒤 보내세요.', recovery: 'EDIT' };
    case 'PAIRING_REVOKED': return { message: '연결이 해제되었습니다. 차량에서 새 QR을 스캔하면 옮겨 보낼 수 있습니다.', recovery: 'NEW_QR' };
    case 'CREDENTIAL_EXPIRED':
    case 'UNAUTHENTICATED': return { message: '연결이 만료되었습니다. 차량에서 새 QR을 스캔하세요.', recovery: 'NEW_QR' };
    case 'DRAFT_EXPIRED': return { message: '전송 서버의 보관 기간이 지났습니다. 차량에서 새 QR을 스캔하면 옮겨 보낼 수 있습니다.', recovery: 'NEW_QR' };
    case 'DRAFT_NOT_FOUND': return { message: '전송 서버에서 이 항목을 찾을 수 없어 차량의 결과를 확인하지 못했습니다.', recovery: 'NONE' };
    default: return { message: `전송 서버가 요청을 거부했습니다(코드: ${code}). 자동으로 다시 시도하지 않습니다.`, recovery: 'EDIT' };
  }
}
