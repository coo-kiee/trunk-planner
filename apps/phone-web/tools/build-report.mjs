// Builds evidence/product/phone-web/report.html from the machine evidence: one standalone Korean page with images embedded.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { evidenceDir, repoRoot } from './lib/evidence.mjs';

const read = (name) => JSON.parse(readFileSync(join(evidenceDir, name), 'utf8'));
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char]));
const image = (file) => `data:image/png;base64,${readFileSync(join(evidenceDir, file)).toString('base64')}`;

const build = read('build.json');
const browser = read('browser.json');
const ds = read('ds-consumption.json');
const visual = read('visual-review.json');
const ces = ['CE-18-03', 'CE-19-04', 'CE-20-04', 'CE-22-04'].map((id) => read(`${id}.json`));
const runDir = join(repoRoot, '..');
const tokensPath = join(runDir, 'nodes/root.phone-web/execute-tokens.json');
const tokens = existsSync(tokensPath) ? JSON.parse(readFileSync(tokensPath, 'utf8')) : null;
const worker = tokens?.agents?.['claude-1'];
const metrics = worker?.latest?.metrics;
const number = (value) => (typeof value === 'number' ? value.toLocaleString('ko-KR') : '미관측');

const badge = (ok, text) => `<span class="badge ${ok ? 'good' : 'bad'}">${escapeHtml(text ?? (ok ? '통과' : '실패'))}</span>`;
const scenarioRows = (scenarios) => scenarios.map((scenario) => `<tr><td>${escapeHtml(scenario.name)}</td><td>${badge(scenario.status === 'passed')}</td><td class="num">${scenario.checks.filter((check) => check.passed).length}/${scenario.checks.length}</td><td class="mono">${escapeHtml(scenario.grade)}</td></tr>`).join('');
const totalChecks = browser.scenarios.reduce((sum, scenario) => sum + scenario.checks.length, 0);
const dsRows = Object.values(ds.runtime.results).flatMap((screens) => ['P0', 'P1', 'P2', 'P3'].flatMap((name) => screens[name].rows));
const toneRows = ds.runtime.results.phone360.P3_states.map((state) => `<tr><td>${escapeHtml(state.state)}</td><td>${escapeHtml(state.icon)} ${escapeHtml(state.label)}</td><td class="mono">${escapeHtml(state.token)}</td><td class="mono">${escapeHtml(state.color)}</td></tr>`).join('');
const overrideRows = ds.runtime.overrides.components.map((row) => `<tr><td>${escapeHtml(row.component)}</td><td class="mono">${escapeHtml(row.token)} → ${escapeHtml(row.override)}</td><td class="mono">${escapeHtml(row.property)}</td><td class="mono">${escapeHtml(row.before)} → ${escapeHtml(row.during)}</td><td>${badge(row.changed, row.changed ? '변함' : '안 변함')}</td></tr>`).join('');
const statusOverrideCount = ds.runtime.overrides.status.filter((row) => row.changed).length;
const figures = visual.viewed.map((entry) => `<figure><img src="${image(entry.file)}" alt="${escapeHtml(entry.caption)}" loading="lazy"><figcaption>${escapeHtml(entry.caption)}</figcaption></figure>`).join('');

const html = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>휴대폰 물품 등록 구현 보고</title>
<style>
:root{--bg:#f6f7f9;--fg:#17202b;--muted:#59677a;--line:#d8dee6;--panel:#fff;--good:#0f6d45;--good-bg:#def3e9;--bad:#a4202c;--bad-bg:#fbe3e5;--warn:#7a5200;--warn-bg:#fdf1d7}
@media (prefers-color-scheme:dark){:root{--bg:#10161e;--fg:#e8edf3;--muted:#9fadbd;--line:#2c3744;--panel:#18212c;--good:#7fe0b4;--good-bg:#123326;--bad:#ffa3ab;--bad-bg:#3a181c;--warn:#f4cf85;--warn-bg:#352a12}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.65 system-ui,-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif}
main{max-width:980px;margin:0 auto;padding:24px 16px 64px}h1{font-size:28px;line-height:1.3;margin:0 0 8px}h2{font-size:21px;margin:40px 0 12px}h3{font-size:17px;margin:24px 0 8px}
p{margin:8px 0}.lead{font-size:18px}.muted{color:var(--muted)}.panel{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:16px;margin:12px 0}
.warn{background:var(--warn-bg);border-color:transparent;color:var(--warn)}table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:8px;border-bottom:1px solid var(--line);vertical-align:top;overflow-wrap:anywhere}th{color:var(--muted);font-weight:600}
.scroll{overflow-x:auto}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}.num{font-variant-numeric:tabular-nums;white-space:nowrap}.badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:13px;white-space:nowrap}.good{background:var(--good-bg);color:var(--good)}.bad{background:var(--bad-bg);color:var(--bad)}
ul{padding-left:20px;margin:8px 0}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:16px}figure{margin:0}figure img{display:block;width:100%;height:auto;border:1px solid var(--line);border-radius:6px}figcaption{font-size:13px;color:var(--muted);margin-top:6px}
</style></head><body><main>
<h1>휴대폰 물품 등록(P0~P3) 구현 보고</h1>
<p class="lead">휴대폰 브라우저에서 사진(선택)과 직접 잰 치수를 등록해 차량으로 보내는 Next.js 앱을 구현했습니다. 실제 Chrome과 실제 릴레이·PostgreSQL로 시험했고, 시험 ${browser.summary.scenarios}개 시나리오 ${totalChecks}개 확인을 모두 통과했습니다.</p>
<div class="panel warn"><strong>독립 검토 대기 중입니다.</strong> 이 보고서는 작성자의 자체 시험 결과입니다. 반대 제공자의 별도 세션 검토를 통과하기 전에는 완료가 아닙니다. 차량 역할은 프로토콜 fixture로 대신했으므로 실제 차량 앱과의 결합, 실제 폰, 실차는 시험하지 않았습니다.</div>

<h2>판정 요약</h2>
<div class="panel scroll"><table><tbody>
<tr><th>빌드</th><td>${badge(build.status === 'passed')}</td><td>정리된 상태에서 생성, 타입 검사, 프로덕션 빌드. Next ${escapeHtml(build.environment.next)}, Node ${escapeHtml(build.environment.node)}</td></tr>
<tr><th>실제 브라우저</th><td>${badge(browser.status === 'passed')}</td><td>${escapeHtml(browser.environment.browser)}, 새 임시 프로필, 360·390·1440 폭</td></tr>
<tr><th>공통 DS 소비</th><td>${badge(ds.status === 'passed')}</td><td>호출 위치 ${ds.static.source_call_sites.length}곳, 계산된 스타일 비교 ${dsRows.length}건 모두 일치, 토큰 덮어쓰기 ${ds.runtime.overrides.components.filter((row) => row.changed).length + statusOverrideCount}건 반영</td></tr>
${ces.map((ce) => `<tr><th>${escapeHtml(ce.id)}</th><td>${badge(ce.status === 'passed')}</td><td>${escapeHtml(ce.responsibility)}</td></tr>`).join('')}
</tbody></table></div>

<h2>구현한 내용</h2>
<div class="panel"><ul>
<li><strong>P0 연결:</strong> QR 토큰은 hydration 전에 주소에서 지우고 메모리에만 둡니다. 이미 열린 탭에서 새 QR을 열어도 같습니다. 비보안 연결, WebCrypto·저장소 없음, 릴레이 평문 주소는 이유와 복구 방법을 보여 주고 연결을 막으며, 토큰을 쓰지 않습니다.</li>
<li><strong>P1 사진·형상:</strong> 사진은 선택이고 전송 동의는 따로 받습니다. 사진은 회전을 반영해 긴 변 2048px·2MiB 이하로 다시 저장하고 EXIF·GPS·원본 파일명을 없앱니다. 사진은 치수에 아무 영향도 주지 않습니다.</li>
<li><strong>P2 치수·취급조건:</strong> 치수에 기본값이 없습니다. 치수·형상·오차·출처를 바꾸면 확정이 풀리고, 확정 전에는 화면뿐 아니라 저장 경계에서도 전송을 거부합니다. 측정앱 연동은 TODO입니다.</li>
<li><strong>P3 전송함:</strong> 대기, 전송 중, 서버 수신, 충돌, 차량 반영, 거부, 교체, 만료, 해제, 미확인, 멈춤을 문구·아이콘·색으로 구분합니다. "서버 수신"과 "차량 반영"을 섞지 않고, 폰의 수정 번호와 차량 확정 revision도 따로 표시합니다.</li>
<li><strong>저장과 재시도:</strong> IndexedDB 트랜잭션이 끝난 뒤에만 "저장됨"으로 표시합니다. 앱이 열려 있을 때만 다시 보내며 service worker는 쓰지 않습니다. CSP의 <span class="mono">worker-src 'none'</span>으로 등록 자체도 차단됩니다.</li>
<li><strong>폰이 하지 않는 일:</strong> 3D 렌더, 충돌·경로·적재 판정, scene 수정은 없습니다. 충돌 때 어느 쪽을 쓸지는 차량에서 정하고, 폰은 차량이 실제로 알려 온 결과만 표시합니다.</li>
</ul></div>

<h2>시험 결과</h2>
<div class="panel scroll"><table><thead><tr><th>시나리오</th><th>결과</th><th>확인</th><th>등급</th></tr></thead><tbody>${scenarioRows(browser.scenarios)}</tbody></table></div>
<div class="panel"><strong>독립 검토 지적 수정(2차):</strong><ul>
<li><strong>PW-R01:</strong> 일부만 손상된 저장 기록이 검증을 통과해 전송함 전체가 영문 오류 화면으로 멈추던 문제를 고쳤습니다. 이제 전송함·초안·연결·확정 기록의 구조 전체를 저장 경계에서 검사합니다. 읽을 수 없는 기록은 원본을 보존한 채 화면과 전송에서 제외하고, 한국어 안내와 "읽을 수 없는 기록만 지우기"를 제공합니다. 예상 못 한 화면 오류도 한국어 안내로 표시합니다.</li>
<li><strong>PW-R02:</strong> 오래된 탭의 전송함 넣기가 다른 탭의 최신 초안을 지우던 문제를 고쳤습니다. 전송함 추가와 초안 삭제를 한 트랜잭션의 비교-교체로 묶어, 충돌하면 아무것도 바꾸지 않고 최신 내용 불러오기를 안내합니다. 저장마다 고유 ID를 써서 같은 번호가 재사용되어 덮어쓰는 경우도 막았습니다.</li>
</ul><p class="muted">손상 기록이 있어도 읽을 수 있는 나머지 항목은 계속 전송합니다. 이 정책 판단은 재검토 대상입니다.</p></div>
<p class="muted">시험 중 찾아 고친 실제 결함: 환경 판정 전 QR 토큰 폐기, 전송 뒤 상태 읽기 예약 누락, 열린 탭의 새 QR 미처리, 전송 중 상태 미표시, 차량의 별도 물품 매핑 미저장, 다른 탭 변경 미통지, 오류 칸 옆 입력칸 어긋남, 이모지 아이콘의 토큰 우회.</p>

<h2>공통 디자인 시스템 실제 소비</h2>
<div class="panel"><p><span class="mono">apps/phone-web/tools/check-screen-ds.mjs</span>가 소스의 실제 호출 위치와 실제 브라우저의 계산된 스타일을 함께 확인합니다. import만 있거나 하드코딩한 음성 fixture ${ds.static.negative_fixtures.length}개는 모두 실패로 잡혔고, 실행 중 DOM에 넣은 하드코딩 버튼도 잡혔습니다. 제품 토큰 원본은 고치지 않았습니다.</p></div>
<h3>상태별 의미 토큰(360px 실제 렌더)</h3>
<div class="panel scroll"><table><thead><tr><th>상태</th><th>표시</th><th>토큰</th><th>계산된 색</th></tr></thead><tbody>${toneRows}</tbody></table></div>
<h3>토큰 값을 바꿨을 때 실제 화면 변화</h3>
<div class="panel scroll"><table><thead><tr><th>컴포넌트</th><th>덮어쓴 토큰</th><th>속성</th><th>변화</th><th>결과</th></tr></thead><tbody>${overrideRows}</tbody></table><p class="muted">상태 색 덮어쓰기 ${statusOverrideCount}/${ds.runtime.overrides.status.length}건도 반영됐습니다. P3의 일부 상태는 앱 저장 경계에 기록을 넣어 재현했고, 실제 전송 흐름으로 나온 상태는 위 시험 결과에 있습니다.</p></div>

<h2>직접 확인한 화면</h2>
<p class="muted">${escapeHtml(visual.method)}</p>
<div class="panel"><ul>${visual.observations.map((item) => `<li>${escapeHtml(item)}</li>`).join('')}</ul></div>
<div class="grid">${figures}</div>

<h2>시험하지 않은 것</h2>
<div class="panel scroll"><table><thead><tr><th>항목</th><th>이유</th></tr></thead><tbody>${browser.not_tested.map((row) => `<tr><td>${escapeHtml(row.item)}</td><td>${escapeHtml(row.reason)}</td></tr>`).join('')}</tbody></table></div>

<h2>알려진 한계와 후속 담당</h2>
<div class="panel"><ul>
<li><strong>QR:</strong> 데스크톱 Chrome에서 합성 PNG를 디코드해 같은 URL로 연결되는 것만 확인했습니다. 실제 폰 스캔이 아닙니다.</li>
<li><strong>TLS:</strong> 자체 인증서와 HTTPS 릴레이로 비-loopback 호스트의 실제 보안 컨텍스트를 확인했습니다. 다만 격리 브라우저에 이 인증서만 임시 허용한 시험이며, 실제 폰 신뢰 설치는 수동 단계로 남았습니다.</li>
<li><strong>만료 시험 값:</strong> 만료 경계는 릴레이의 짧은 TTL 시험 설정으로 실제 시각을 기다려 확인했습니다. 제품 TTL(QR 5분, 초안 24시간, 연결 24시간)로 기다린 것은 아닙니다.</li>
<li><strong>오프라인:</strong> 열린 페이지에서는 작성·저장·화면 이동이 되지만, 오프라인 새로고침과 최초 접속은 되지 않습니다.</li>
<li><strong>저장소:</strong> 실제 저장소 축출은 유발하지 않았고 quota 오류는 주입으로 시험했습니다. 저장소 암호화는 보장하지 않습니다.</li>
<li><strong>개발 의존성:</strong> <span class="mono">npm audit</span>에 ajv 8.17.1의 보통 등급 항목(<span class="mono">$data</span> 옵션 ReDoS) 1건이 있습니다. 공통 계약과 같은 버전으로 빌드 때만 쓰고 <span class="mono">$data</span>를 쓰지 않으며, 운영 의존성 감사는 0건입니다.</li>
<li><strong>저장소 루트:</strong> 루트 <span class="mono">npm ci</span>는 lock에 이 workspace가 없어 실패합니다. 루트 lock은 이 담당의 소유가 아니어서 고치지 않았고 상위에 알렸습니다. 이 앱은 <span class="mono">npm --prefix apps/phone-web ci --no-workspaces</span>로 재현됩니다.</li>
<li><strong>후속 e2e:</strong> 실제 차량 앱과의 결합, QR의 실폰 스캔, 양쪽 화면의 상태 비교.</li>
</ul></div>

<h2>담당과 사용량</h2>
<div class="panel scroll"><table><tbody>
<tr><th>작업 / 단계 / 시도</th><td>root.phone-web / execute / 수정 1회차(같은 native 세션 재사용)</td></tr>
<tr><th>담당 / 제공자</th><td>claude-1 / Anthropic Claude</td></tr>
<tr><th>관측 모델</th><td class="mono">claude-opus-5-5 (세션 자체 보고)</td></tr>
<tr><th>설정 모델</th><td>미관측</td></tr>
<tr><th>native 세션</th><td class="mono">${escapeHtml(worker?.native_id ?? '미관측')}</td></tr>
<tr><th>입력(캐시 포함 총량)</th><td class="num">${number(metrics?.input_total)}</td></tr>
<tr><th>캐시 읽기 / 캐시 생성(입력의 부분집합)</th><td class="num">${number(metrics?.input_cached)} / ${number(metrics?.cache_creation)}</td></tr>
<tr><th>출력 / 추론(출력의 부분집합)</th><td class="num">${number(metrics?.output_total)} / ${number(metrics?.reasoning_output)}</td></tr>
<tr><th>출처</th><td>controller의 <span class="mono">execute-tokens.json</span> 마지막 관측값(${worker?.latest?.observed_at ? new Date(worker.latest.observed_at * 1000).toISOString() : '미관측'}). 이 보고서를 쓰는 동안의 사용량은 포함되지 않으며, 최종값은 controller가 갱신합니다. 미관측은 0이 아닙니다.</td></tr>
</tbody></table></div>
<p class="muted">증거: build.json, browser.json, ds-consumption.json, CE-18-03.json, CE-19-04.json, CE-20-04.json, CE-22-04.json, visual-review.json. 기존 원본, 3100·3101 서버, AVD, 다른 예약, 계정, 비밀은 건드리지 않았고 외부 제출·호스팅·결제는 하지 않았습니다.</p>
</main></body></html>`;
writeFileSync(join(evidenceDir, 'report.html'), html);
console.log(JSON.stringify({ event: 'report_written', bytes: Buffer.byteLength(html) }));
