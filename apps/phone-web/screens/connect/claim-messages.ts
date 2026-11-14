export function claimFailureMessage(code: string): string {
  switch (code) {
    case 'PAIRING_TOKEN_CONSUMED': return '이미 사용된 QR입니다. 다른 기기나 탭에서 먼저 연결했을 수 있습니다. 차량 화면에서 새 QR을 만들어 스캔하세요.';
    case 'PAIRING_EXPIRED': return 'QR이 만료되었습니다(5분). 차량 화면에서 새 QR을 만들어 스캔하세요.';
    case 'PAIRING_REVOKED': return '차량에서 이 연결을 해제했습니다. 차량 화면에서 새 QR을 만들어 스캔하세요.';
    case 'UNAUTHENTICATED':
    case 'QR_MALFORMED': return 'QR 주소가 올바르지 않습니다. 차량 화면의 QR을 다시 스캔하세요.';
    case 'RATE_LIMITED': return '요청이 너무 많습니다. 잠시 후 다시 연결을 눌러 주세요.';
    case 'CONNECTION_FAILED': return '전송 서버에 닿지 못했습니다(네트워크 또는 설정 문제). 다시 연결을 누르세요. 페이지를 새로고침하면 QR을 다시 스캔해야 합니다.';
    case 'ENVIRONMENT_BLOCKED': return '이 환경에서는 안전하게 연결할 수 없어 QR을 사용하지 않았습니다. 위 안내를 해결한 뒤 QR을 다시 스캔하세요.';
    case 'LOCAL_SAVE_FAILED': return '연결 정보를 이 기기에 저장하지 못했습니다. 저장 공간을 확인한 뒤 차량 화면에서 새 QR을 스캔하세요.';
    default: return `연결이 거부되었습니다(코드: ${code}). 차량 화면에서 새 QR을 만들어 스캔하세요.`;
  }
}
