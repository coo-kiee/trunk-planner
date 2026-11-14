// Builds the standalone Korean report strictly from recorded evidence, so its numbers cannot drift from the runs.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { relayRoot } from '../../src/config.ts';

const evidenceDir = resolve(relayRoot, '../../evidence/product/relay');
const read = (name: string) => JSON.parse(readFileSync(resolve(evidenceDir, name), 'utf8'));
const postgres = read('postgres.json');
const protocol = read('protocol.json');
const ces = ['CE-18-01', 'CE-19-01', 'CE-20-01', 'CE-22-05'].map((id) => read(`${id}.json`));

const esc = (value: unknown) => String(value).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const STATUS: Record<string, string> = { passed: '통과', failed: '실패', blocked: '차단', incomplete: '미완료' };
const GRADE: Record<string, string> = {
  real_postgres_local_integration: '실제 PostgreSQL 로컬 통합',
  local_tls_real_postgres: '로컬 자체서명 TLS + 실제 PostgreSQL',
  unit: '단위 (DB 없음)',
};
const badge = (status: string) => `<span class="badge ${status === 'passed' ? 'ok' : 'bad'}">${esc(STATUS[status] ?? status)}</span>`;
const CE_MEANING: Record<string, string> = {
  'CE-18-01': '같은 초안을 다시 보내도 한 번만 처리되고, 만료·해제된 토큰은 거부되며, 재시작 후에도 ack가 복구됩니다.',
  'CE-19-01': '차량이 판단한 충돌과 수락 revision을 그대로 전달하고, 상태 CAS로 중복 응답을 막습니다. 릴레이는 revision을 판단하지 않습니다.',
  'CE-20-01': '릴레이와 DB가 종료되거나 강제 종료된 뒤에도 미수락 초안·ack·해제 상태와 TTL이 복구됩니다.',
  'CE-22-05': '이 담당의 모든 결과에 근거 등급을 표시했습니다.',
};
const CASE_KO: Record<string, string> = {
  'prod-config-ttl-stamps': '운영 설정 TTL 300초·24시간이 DB 시각으로 기록됨',
  'concurrent-claim-exactly-one': '동시 QR claim 12건 중 정확히 1건 성공',
  'claim-response-lost-requires-new-qr': 'claim 응답 유실 시 비밀 재발급 없이 새 QR 필요',
  'role-and-cross-pair-authorization': '역할·pairing 간 접근 차단, 미상 토큰 균일 401',
  'duplicate-is-same-receipt': '동일 재전송은 같은 영수증, 행 1개',
  'same-key-different-content-409': '같은 키의 다른 내용은 409, 저장값 유지',
  'unique-constraint-direct-sql': 'DB 제약이 직접 중복과 종료 후 내용 보관을 거부',
  'supersede-and-stale-revision': '새 revision이 이전을 대체, 늦은 revision과 늦은 ack 거부',
  'conflict-then-accepted-revision-relayed': '충돌 후 수락 revision을 폰까지 그대로 전달',
  'concurrent-ack-cas': '서로 다른 ack 동시 경쟁에서 1건만 적용',
  'revoke-blocks-and-purges': '해제 후 모든 접근 거부, 내용·사진 삭제, ack 보존',
  'revoke-submit-race': '제출과 해제 경쟁 후 활성 초안 없음',
  'draft-expiry-reads-without-cleanup': 'cleanup 전에도 만료 조회, 이후 내용 삭제',
  'credential-expiry-then-cleanup-no-new-accept': '자격 만료 직전·직후·cleanup 후 새 수락 0건',
  'cleanup-skip-locked-and-idempotent': 'cleanup은 잠긴 행을 건너뛰고 반복해도 안전',
  'relay-sigterm-restart': '릴레이 SIGTERM 후 재시작 복구',
  'relay-sigkill-restart-under-load': '요청 처리 중 릴레이 SIGKILL 후 재시도 멱등',
  'postgres-sigkill-recovery': 'PostgreSQL SIGKILL 때 503, 복구 후 재연결·데이터 유지',
  'ttl-applies-across-relay-downtime': '릴레이 중단 중에도 만료가 DB 시각으로 적용',
  'migration-up-rerun-checksum-down': 'migration 적용·재실행·변조 거부·down·동시 실행',
  'db-role-separation': 'runtime role의 DDL·설정 변경 거부, 잘못된 암호 거부',
  'log-and-storage-redaction': '로그·표·데이터 파일에 비밀값 누출 없음',
  'photo-policy-allow-and-reject': '사진 형식·메타데이터·크기 정책',
  'consent-and-envelope-policy': '전송 동의, 사진 별도 동의, 금지 필드 거부',
  'body-size-limits-streaming': '스트리밍 본문 크기 상한',
  'url-bearer-refused': 'URL의 토큰 거부',
  'expiry-during-row-lock-wait': '행 잠금 대기 중 만료된 claim·ack·제출 거부',
  'photo-metadata-any-position-over-http': '스캔 뒤·스캔 사이·EOI 뒤 메타데이터를 실제 HTTP/DB에서 거부',
  'photo-minimal-receive-roundtrip': '사진 최소 수신, 같은 차량만 열람, 종료 즉시 삭제',
  'local-tls-non-demo-listener': '로컬 TLS로만 비데모 기동 (공개 HTTPS 아님)',
};

const caseRows = (suite: { cases: { id: string; grade: string; passed: boolean }[] }) => suite.cases
  .filter((c) => CASE_KO[c.id])
  .map((c) => `<tr><td>${esc(CASE_KO[c.id])}<div class="mono">${esc(c.id)}</div></td><td>${esc(GRADE[c.grade] ?? c.grade)}</td><td>${badge(c.passed ? 'passed' : 'failed')}</td></tr>`)
  .join('');

const sigkill = postgres.cases.find((c: { id: string }) => c.id === 'relay-sigkill-restart-under-load')?.actual;
const redaction = postgres.cases.find((c: { id: string }) => c.id === 'log-and-storage-redaction')?.actual;
const overall = postgres.status === 'passed' && protocol.status === 'passed';

const html = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>릴레이 구현 보고</title>
<style>
:root{color-scheme:light dark;--bg:#f7f7f5;--panel:#fff;--ink:#1d1f21;--mute:#5d6369;--line:#dfe1e3;--ok:#116b3a;--okbg:#e4f4ea;--bad:#a12020;--badbg:#fbe7e7;--warn:#7a5200;--warnbg:#fbf2dc}
@media (prefers-color-scheme:dark){:root{--bg:#16181a;--panel:#1f2225;--ink:#ececec;--mute:#a4a9ae;--line:#34383c;--ok:#7fdca5;--okbg:#17352a;--bad:#f0a3a3;--badbg:#3d1f1f;--warn:#e8c675;--warnbg:#39301a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.65 -apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif}
main{max-width:920px;margin:0 auto;padding:32px 16px 64px}h1{font-size:26px;margin:0 0 8px}h2{font-size:19px;margin:36px 0 10px}p{margin:8px 0}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:16px;margin:12px 0}
.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:8px 6px;overflow-wrap:anywhere;border-top:1px solid var(--line);vertical-align:top}th{color:var(--mute);font-weight:600;border-top:0}
.badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:13px;white-space:nowrap}.ok{color:var(--ok);background:var(--okbg)}.bad{color:var(--bad);background:var(--badbg)}.warn{color:var(--warn);background:var(--warnbg)}
.mono{font:12px ui-monospace,Menlo,monospace;color:var(--mute);overflow-wrap:anywhere}.lead{color:var(--mute)}ul{padding-left:20px;margin:8px 0}li{margin:4px 0}
</style></head><body><main>
<h1>휴대폰-차량 릴레이 구현 보고</h1>
<p class="lead">담당 범위: <span class="mono">services/relay</span>, <span class="mono">evidence/product/relay</span> · 독립 재검토 전 보고서 ${badge('incomplete').replace('미완료', '재검토 대기')}</p>

<div class="panel"><strong>결론.</strong> 단일 Node.js 릴레이와 PostgreSQL 저장소를 구현했고, 실제 PostgreSQL ${esc(postgres.environment.postgres_binary_version?.replace('postgres (PostgreSQL) ', '') ?? '')}에서 두 시험을 실행해
<strong>test:postgres ${postgres.summary.passed}/${postgres.summary.total}</strong>, <strong>test:protocol ${protocol.summary.passed}/${protocol.summary.total}</strong> ${overall ? '모두 통과했습니다' : '중 실패가 있습니다'}.
이 결과는 릴레이 자체의 프로토콜과 복구만 증명합니다. <strong>휴대폰 화면, 차량 화면, 실제 결합, 공개 HTTPS, 실폰, 실차는 시험하지 않았습니다.</strong></div>

<h2>독립 검토 지적 수정</h2>
<div class="panel scroll"><table><thead><tr><th>지적</th><th>수정</th><th>재시험</th></tr></thead><tbody>
<tr><td>R01 JPEG 첫 스캔 뒤의 EXIF를 저장·전달함</td><td>JPEG 전체 marker와 스캔 경계를 끝까지 검사하고, 스캔 뒤 메타데이터와 EOI 뒤 데이터를 거부합니다. WebP도 허용 청크만 받습니다.</td><td>${badge(protocol.cases.find((c: { id: string }) => c.id === 'photo-metadata-any-position-over-http')?.passed ? 'passed' : 'failed')}</td></tr>
<tr><td>R02 행 잠금 대기 중 만료된 QR을 claim함</td><td>잠금 획득 뒤 새 DB 시각으로 만료를 판정하고 claim과 ack 쓰기에도 유효 조건을 넣었습니다. 공통 자격 검사도 같은 방식으로 바꿨습니다.</td><td>${badge(postgres.cases.find((c: { id: string }) => c.id === 'expiry-during-row-lock-wait')?.passed ? 'passed' : 'failed')}</td></tr>
</tbody></table></div>
<p class="lead">재시험은 작성자가 실행했습니다. 수정의 인정 여부는 반대 제공자의 재검토가 결정합니다.</p>

<h2>완료 기준별 결과</h2>
<div class="panel scroll"><table><thead><tr><th>항목</th><th>의미</th><th>결과</th></tr></thead><tbody>
${ces.map((c) => `<tr><td class="mono" style="white-space:nowrap">${esc(c.id)}</td><td>${esc(CE_MEANING[c.id])}</td><td>${badge(c.status)}</td></tr>`).join('')}
</tbody></table></div>
<p class="lead">CE-18, CE-19, CE-20, CE-22의 전체 완료는 다른 담당과 e2e 결합 시험이 끝나야 합니다. 여기의 통과는 relay 하위 항목뿐입니다.</p>

<h2>구현한 내용</h2>
<div class="panel"><ul>
<li>차량용과 휴대폰용 경로를 분리했고 QR 주소, 폰 릴레이 주소, 차량 릴레이 주소를 각각 설정합니다.</li>
<li>TTL은 공용 계약 값(페어링 5분, 초안 24시간, ack 24시간)을 설정 버전으로 고정해 읽습니다. 이 값은 잠정값입니다.</li>
<li>토큰은 256비트 난수이며 DB에는 해시만 저장합니다. 동일 초안은 유일 제약과 내용 해시로 멱등 처리합니다.</li>
<li>서버는 scene을 쓰지 않습니다. 차량이 보낸 수락·충돌 결과를 전달할 뿐이며 scene 경로와 표가 없음을 확인했습니다.</li>
<li>종료·해제 즉시 물품 내용과 사진을 지우며, 이를 DB 제약으로도 강제합니다.</li>
<li>사진은 별도 동의가 있을 때만 받고 파일 구조 전체를 검사해 EXIF 등 메타데이터를 거부합니다. 실제 축소·EXIF 제거·동의 화면은 phone-web 담당입니다.</li>
</ul></div>

<h2>실제 PostgreSQL 시험</h2>
<div class="panel scroll"><table><thead><tr><th>시험</th><th>등급</th><th>결과</th></tr></thead><tbody>${caseRows(postgres)}</tbody></table></div>
<p class="lead">요청 처리 중 강제 종료 시험: 전달된 요청 ${esc(sigkill?.firstRoundDelivered)}건, 유실된 요청 ${esc(sigkill?.firstRoundLost)}건, 재시도 후 키당 1행(${esc(sigkill?.counts?.rows)}행).</p>

<h2>프로토콜·보안 시험</h2>
<div class="panel scroll"><table><thead><tr><th>시험</th><th>등급</th><th>결과</th></tr></thead><tbody>${caseRows(protocol)}</tbody></table></div>
<p class="lead">누출 검사: 릴레이 로그 ${esc(redaction?.scannedRelayLogBytes)}바이트, PostgreSQL 로그 ${esc(redaction?.scannedPgLogBytes)}바이트, 데이터 파일 ${esc(redaction?.scannedDataFiles)}개에서 누출 ${esc(redaction?.leaks?.length)}건. 테스트 데이터는 모두 합성입니다.</p>

<h2>시험하지 않은 것</h2>
<div class="panel scroll"><table><thead><tr><th>항목</th><th>상태</th><th>설명</th></tr></thead><tbody>
<tr><td>공인 HTTPS·호스팅</td><td><span class="badge warn">미실행</span></td><td>승인되지 않았습니다. 자체서명 로컬 TLS만 시험했으며 공개 연결 성공이 아닙니다.</td></tr>
<tr><td>백업·복원</td><td><span class="badge warn">미실행</span></td><td>격리 도구에 pg_dump가 없습니다. 기본 정책은 백업 없음입니다.</td></tr>
<tr><td>저장 암호화</td><td><span class="badge warn">미실행</span></td><td>디스크·백업 암호화는 관측하지 않았습니다. TLS 종단과 DB 관리자는 내용을 볼 수 있습니다.</td></tr>
<tr><td>삭제의 물리 소거</td><td><span class="badge warn">미확인</span></td><td>논리 삭제와 조회 차단만 확인했습니다. vacuum 전 파일·WAL에는 남을 수 있습니다.</td></tr>
<tr><td>사진 실제 디코드</td><td><span class="badge warn">범위 밖</span></td><td>파일 구조만 검사합니다. 시험 입력은 합성 바이트와 데스크톱 Chrome canvas 출력이며 모바일 브라우저는 실행하지 않았습니다.</td></tr>
<tr><td>폰·차량 UI, 차량 단독 흐름, 실제 결합</td><td><span class="badge warn">미실행</span></td><td>phone-web, vehicle, e2e 담당입니다.</td></tr>
<tr><td>실폰·실차</td><td><span class="badge warn">미실행</span></td><td>실차 미시험입니다.</td></tr>
<tr><td>대량 부하</td><td><span class="badge warn">미실행</span></td><td>동시성 반례만 시험했습니다. 처리량은 측정하지 않았습니다.</td></tr>
</tbody></table></div>

<h2>알아야 할 제약</h2>
<div class="panel"><ul>
<li>시험용 PostgreSQL은 고정 버전 <span class="mono">embedded-postgres@${esc(postgres.environment.embedded_postgres.version)}</span>(MIT)입니다. 베타 배포판으로 격리 시험 도구일 뿐이며 운영 DB 경로가 아닙니다.</li>
<li><span class="mono">services/relay</span>를 추가했으므로 루트 <span class="mono">npm ci</span>를 위해 루트 lock 동기화가 필요합니다. 루트는 foundation 소유라 수정하지 않고 상위에 알렸습니다.</li>
<li>속도 제한은 프로세스 메모리 기반이라 재시작 시 초기화되며, 프록시 뒤에서는 다시 설계해야 합니다.</li>
</ul></div>

<h2>기록</h2>
<div class="panel"><ul>
<li>시험: <span class="mono">npm --prefix services/relay run test:postgres</span> (종료 코드 ${esc(postgres.exit)}), <span class="mono">test:protocol</span> (종료 코드 ${esc(protocol.exit)})</li>
<li>환경: Node ${esc(postgres.environment.node)}, ${esc(postgres.environment.platform)}. <span class="mono">${esc(postgres.environment.postgres_select_version)}</span></li>
<li>소스 digest: <span class="mono">${esc(postgres.inputs.relay_source.digest)}</span> (${esc(postgres.inputs.relay_source.files)}개 파일)</li>
<li>기계 증거: <span class="mono">postgres.json, protocol.json, CE-18-01.json, CE-19-01.json, CE-20-01.json, CE-22-05.json</span></li>
<li>문서: <span class="mono">services/relay/docs/protocol-v1.md, threat-model-v1.md, operations-v1.md</span></li>
<li>작업자: Claude <span class="mono">claude-opus-5-5</span> (구성값), 토큰 사용량은 이 세션에서 관측하지 못해 <strong>미확인</strong>이며 0이 아닙니다. 정확한 값은 최종 보고의 worker-accounting을 따릅니다.</li>
<li>독립 교차검토: 1차 검토에서 R01, R02 지적을 받아 수정했으며 재검토 대기입니다. 검토 기록은 <span class="mono">evidence/product/relay/independent-review/</span>에 보존했습니다.</li>
</ul></div>
</main></body></html>
`;
writeFileSync(resolve(evidenceDir, 'report.html'), html);
console.log('report.html written');
