// Pure judgement of whether this browser may pair and transfer. Effects live in the caller, so every reason is testable.
import { isLoopbackHost, type RelayUrlCheck } from '../config/relay-url.ts';

export type EnvironmentBlock =
  | 'INSECURE_CONTEXT'
  | 'WEBCRYPTO_MISSING'
  | 'INDEXEDDB_MISSING'
  | 'RELAY_NOT_CONFIGURED'
  | 'RELAY_CLEARTEXT'
  | 'MIXED_CONTENT';

export interface EnvironmentFacts {
  isSecureContext: boolean;
  hasSubtleCrypto: boolean;
  hasIndexedDb: boolean;
  pageProtocol: string;
  relay: RelayUrlCheck;
}

export function judgeEnvironment(facts: EnvironmentFacts): EnvironmentBlock[] {
  const blocks: EnvironmentBlock[] = [];
  if (!facts.isSecureContext) blocks.push('INSECURE_CONTEXT');
  if (!facts.hasSubtleCrypto) blocks.push('WEBCRYPTO_MISSING');
  if (!facts.hasIndexedDb) blocks.push('INDEXEDDB_MISSING');
  if (!facts.relay.ok) {
    blocks.push(facts.relay.reason === 'CLEARTEXT_NON_LOOPBACK' ? 'RELAY_CLEARTEXT' : 'RELAY_NOT_CONFIGURED');
    return blocks;
  }
  const relayUrl = new URL(facts.relay.url);
  if (facts.pageProtocol === 'https:' && relayUrl.protocol === 'http:' && !isLoopbackHost(relayUrl.hostname)) blocks.push('MIXED_CONTENT');
  return blocks;
}

export const ENVIRONMENT_MESSAGES: Record<EnvironmentBlock, { title: string; recovery: string }> = {
  INSECURE_CONTEXT: {
    title: '보안 연결(HTTPS)이 아닙니다',
    recovery: '주소가 https:// 로 시작하는지 확인하고, 차량 화면의 QR을 다시 스캔하세요. 이 상태에서는 연결과 전송을 막습니다.',
  },
  WEBCRYPTO_MISSING: {
    title: '이 브라우저에서 암호 기능(WebCrypto)을 쓸 수 없습니다',
    recovery: 'HTTPS 주소로 다시 열거나 최신 브라우저를 사용하세요. 사진 확인값을 만들 수 없어 전송을 막습니다.',
  },
  INDEXEDDB_MISSING: {
    title: '이 브라우저에 작성 내용을 저장할 수 없습니다',
    recovery: '시크릿 모드나 저장 차단 설정을 끄고 다시 여세요. 저장되지 않으면 작성 내용을 잃을 수 있습니다.',
  },
  RELAY_NOT_CONFIGURED: {
    title: '전송 서버 주소가 설정되지 않았습니다',
    recovery: '이 앱을 실행한 담당자가 PHONE_RELAY_BASE_URL 을 설정해야 합니다. 작성 내용은 이 기기에 남습니다.',
  },
  RELAY_CLEARTEXT: {
    title: '전송 서버 주소가 암호화되지 않았습니다',
    recovery: '전송 서버는 https 주소여야 합니다(이 컴퓨터 안에서 하는 개발 시험만 예외). 담당자에게 설정 변경을 요청하세요.',
  },
  MIXED_CONTENT: {
    title: '보안 페이지에서 암호화되지 않은 서버로 보낼 수 없습니다',
    recovery: '전송 서버를 https 주소로 바꿔야 합니다. 작성 내용은 이 기기에 남습니다.',
  },
};
