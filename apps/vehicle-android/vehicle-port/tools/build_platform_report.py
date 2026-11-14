#!/usr/bin/env python3
"""Korean standalone report plus the CE-22-02 grade table. Every number comes from the evidence files written by the checks."""
import html, json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from emu_lib import EVIDENCE, ROOT, WORKER, sha256, write_json  # noqa: E402

# Proof that the verified upstream sources stayed byte-identical, compared with vehicle-core's own recorded digest.
sys.path.insert(0, str(ROOT / 'apps/vehicle-android/data/device-test'))
import source_digest  # noqa: E402
recorded = json.loads((ROOT / 'evidence/product/vehicle-core/source-manifest.json').read_text())
upstream_same = source_digest.manifest() == recorded['files']
write_json('preservation.json', {'upstream_vehicle_core_sources_unchanged': upstream_same, 'digest_recorded': recorded['digest'], 'digest_now': source_digest.digest(),
                                 'note': '작업 중 --write-locks가 상류 lockfile의 수정시각을 갱신했으나 내용 hash는 기록과 같다. 소스는 수정하지 않았다.',
                                 'not_touched': ['기존 서버 3100/3101', '다른 AVD·emulator', '기존 DB', '계정·비밀', 'SDK/AAR 재배포', '외부 제출·호스팅·결제'], 'avd_used': 'platform_checks (emulator-5574, adb 5237)'})

load = lambda name: json.loads((EVIDENCE / name).read_text())
sdk, ds, sync = load('sdk.json'), load('ds.json'), load('sync.json')
esc = lambda value: html.escape(str(value))

rows = []
for step in sdk['steps']:
    rows.append(('port', step['name'], step['grade'], '통과' if step['passed'] else '실패'))
rows.append(('port', f"JVM fixture {sdk['unit_fixture_count']}건", 'fixture', '통과' if all(t['status'] == 'passed' for t in sdk['unit_fixture']) else '실패'))
rows.append(('designsystem', f"렌더 smoke {len(ds['runtime'].get('tests', []))}건·토큰 주입 관측 {len(ds['injected_value_observations'])}건", 'emulator', '통과' if ds['passed'] else '실패'))
rows.append(('sync', f"fixture {sync['fixture']['count']}건", 'fixture', '통과' if all(t['status'] == 'passed' for t in sync['fixture']['tests']) else '실패'))
rows.append(('sync', '실제 Room·Keystore·프로세스 종료+실제 릴레이·격리 PostgreSQL', 'local_integration(emulator)', {'passed': '통과', 'failed': '실패'}.get(sync['local_integration']['status'], '미실행')))
ce22 = {'schema': 'vehicle-platform evidence v1', **WORKER, 'case': 'CE-22-02', 'responsibility': '자기 port 결과에 fixture/emulator 구분', 'grades_used': sorted({r[2] for r in rows}), 'real_vehicle': 'not_tested', 'real_phone': 'not_tested',
        'rows': [{'area': a, 'check': n, 'grade': g, 'result': r} for a, n, g, r in rows], 'not_run': sdk['not_run'],
        'passed': all(r[3] == '통과' for r in rows), 'grade': '각 행에 표기'}
write_json('CE-22-02.json', ce22)

passed = ce22['passed'] and sdk['passed'] and ds['passed'] and sync['passed'] and upstream_same
table = ''.join(f'<tr><td>{esc(a)}</td><td>{esc(n)}</td><td>{esc(g)}</td><td class="{"ok" if r == "통과" else "bad"}">{esc(r)}</td></tr>' for a, n, g, r in rows)
notrun = ''.join(f"<li>{esc(n['case'])}: {esc(n['reason'])}</li>" for n in sdk['not_run'])
ready = next((s for s in sdk['steps'] if s['name'].startswith('초기 연결 경로')), {})
artifacts = sorted(p.name for p in EVIDENCE.glob('*.json'))
hashes = ''.join(f"<tr><td>{esc(p)}</td><td><code>{esc(a['sha256'])}</code></td></tr>" for p, a in sdk['artifacts'].items())
page = f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>차량 플랫폼 결과</title><style>
:root{{--bg:#f7f8fa;--fg:#1a2230;--mute:#566273;--line:#d7dde6;--ok:#0f7a4f;--bad:#b3261e;--card:#fff}}
@media (prefers-color-scheme:dark){{:root{{--bg:#101b2b;--fg:#f1f5f9;--mute:#9cafc5;--line:#2b3c55;--ok:#38c98b;--bad:#ef6973;--card:#1b2b41}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:16px/1.65 system-ui,"Apple SD Gothic Neo",sans-serif}}
main{{max-width:960px;margin:auto;padding:24px 16px}}h1{{font-size:26px;margin:.2em 0}}h2{{font-size:19px;margin:1.6em 0 .4em}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}}.mute{{color:var(--mute)}}
.scroll{{overflow-x:auto}}table{{border-collapse:collapse;width:100%;font-size:14px}}th,td{{border-bottom:1px solid var(--line);padding:7px 8px;text-align:left;vertical-align:top}}
.ok{{color:var(--ok);font-weight:600}}.bad{{color:var(--bad);font-weight:600}}code{{font-size:12px;word-break:break-all}}</style></head><body><main>
<h1>SDK·릴레이 클라이언트·디자인 시스템 결과</h1>
<p class="mute">7차 시도. 실제 통합에서 나온 차단 결함 F-01을 수정했습니다. 독립 검토의 R1~R5 반려와 DS 로그 수집 시간초과 수정도 유지합니다. 이를 수정했고 반대 제공자 재검토 대기 중입니다. 재검토 통과 전에는 완료로 보지 않습니다. 실차·실폰 미시험.</p>
<div class="card"><b>자체 검사 결과: <span class="{'ok' if passed else 'bad'}">{'전부 통과' if passed else '실패 있음'}</span></b><br>
공식 Vehicle SDK 2.3.2와 CarUxRestrictions를 포트 안에 가뒀고, 격리 에뮬레이터에서 실제 콜백으로 동작을 확인했습니다. 제품 화면은 이 범위가 아니며 vehicle-ui가 맡습니다.</div>
<h2>F-01: 연결 5분 뒤 차량이 스스로 만료되던 결함</h2><ul>
<li><b>재현</b>: 수정 전 코드에서 실제 릴레이·PostgreSQL·Room으로 300초를 실제로 기다렸습니다. 서버 자격은 유효한데 차량 Room은 EXPIRED가 됐고 폰이 보낸 초안을 받지 못했습니다(F-01-before.json).</li>
<li><b>수정</b>: claim 전에는 Room 연결 행을 만들지 않습니다. 서버 상태에서 claim을 확인한 뒤 서버의 실제 만료값으로 저장하고 행을 만듭니다. 차량이 임의로 만료를 늘리지 않습니다. 저장 만료가 지나도 서버가 답하기 전에는 만료로 표시하지 않고, 오프라인이면 미전송 ack를 보존합니다.</li>
<li><b>회귀 확인</b>: 미claim QR 만료, 짧은 자격의 실제 만료, 해제는 그대로 동작합니다. <b>:data는 수정하지 않았습니다.</b></li>
<li><b>간헐 현상</b>: 전체 검사 1회에서 미claim 만료 시험의 온라인 poll이 NetworkUnavailable을 돌려줬습니다. 그때의 전송 오류 내용은 기록되지 않아 원인은 미확인이고, 이후 단독 2회·전체 2회는 통과했습니다. 시험은 전송 오류가 기록된 경우에만 poll을 반복합니다.</li>
<li><b>한계</b>: 수정 전 APK로 만든 연결 행은 만료를 고칠 수 없어 새 QR이 필요합니다(상태로 알림). 원래 E2E 반례(E2E-CORE, E2E-CE-19)와 새 APK 빌드·해시 기록은 후속 담당이 재실행해야 합니다.</li></ul>
<h2>검토 반려 수정</h2><ul>
<li><b>R1</b>(같은 초안 key의 내용 교체 뒤 다른 내용을 수락했다고 보고): 처음 받은 hash를 덮어쓰지 않고, 내용이 달라지면 key를 영속 격리합니다. 격리되면 결정은 Quarantined로 거부되고 ack를 보내지 않으며, 재시작 뒤에도 유지됩니다. 격리 전에 커밋된 결정은 되돌리지 않고 미전달로 끝냅니다(REJECTED). 실제 Room·프로세스 재생성에서도 확인했습니다. 실제 릴레이는 내용 교체를 거부하므로 응답을 시험에서 변조해 유발했습니다.</li>
<li><b>R2</b>(권한 오류 뒤에도 제안 방출): 등록마다 별도 listener 신분을 두어 오류·단절 시 무효화합니다. 오류 뒤 callback과 늦은 read, 재연결 뒤 옛 등록의 callback은 위치와 제안을 만들지 않습니다. 실행 중 권한 철회는 실제로 유발할 수 없어 fixture 등급입니다.</li>
<li><b>R3</b>(등록 도중 오류를 잃고 사용가능으로 표시): 등록 호출 전에 listener 소유권을 세웁니다. 등록 중 오류·단절이면 사용가능 표시와 read를 시작하지 않습니다. 등록 중 받은 값은 기준선으로만 쓰고 제안을 내지 않습니다. 재연결이 반복되면 제한된 횟수 뒤 Failed로 끝납니다. 실제 SDK에서 이런 동작을 관측하지 못해 fixture 등급입니다.</li>
<li><b>R4</b>(오류 뒤 unregister 도중 들어온 callback이 제안 발행): SDK 호출 전에 listener 소유권과 기준선을 먼저 끊습니다. unregister 중 값·오류·단절은 위치, 제안, 추가 재연결을 만들지 않습니다. 같은 패턴을 UX 쪽에서도 점검해, 시작 실패 뒤 남은 listener가 제한을 풀지 못하게 했습니다. 역시 실제 SDK 미관측이라 fixture 등급입니다.</li>
<li><b>R5</b>(UX 시작 도중 해제한 상태를 시작 흐름이 덮어써 편집 제한을 다시 엶): 시작 시도가 SDK 호출 뒤와 상태 쓰기 전마다 자신이 아직 유효한지 확인합니다. 해제된 시도는 SDK를 더 호출하지 않고, 늦은 오류도 쓰지 않습니다. 같은 동기 흐름을 Door의 초기화·해제·상태 발행 뒤에도 막았습니다. 실제 SDK 미관측이라 fixture 등급입니다.</li></ul>
<h2>구현한 것</h2><ul>
<li><b>vehicle-port</b>: 초기화·capability·listener·해제를 내부에 가두고 준비대기/사용가능/미지원/권한없음/실패/해제 상태를 구분합니다. SDK 타입은 밖으로 나가지 않습니다.</li>
<li><b>주행 제한</b>: requiresDO로 편집·QR 허용과 렌더 중단 신호를 만듭니다. 원본 mask는 보존하고, 알 수 없으면 항상 막습니다. distraction optimization은 선언하지 않았습니다.</li>
<li><b>트렁크 Door REAR</b>: 0→100은 적재 체크리스트 제안, 100→0은 저장확인 제안만 냅니다. 자동저장·적재감지는 구조적으로 없습니다(port가 Room에 의존하지 않음).</li>
<li><b>Navi 도착</b>: 미관측이라 기본꺼짐. CarInfo 형상추정·좌석제어·Gear 판단은 없습니다.</li>
<li><b>sync</b>: 차량 outbound 호출과 폰 페이지 URL을 분리하고, 멱등 수신·명시 결정·충돌·ack 재전송·해제를 구현했습니다. 입력은 InboundRepository뿐이라 폰이 차량 scene에 쓸 경로가 없습니다.</li>
<li><b>designsystem</b>: Button/Field/Status/Panel이 생성 토큰을 실제로 읽습니다. 테마 값을 바꾸면 렌더 픽셀과 크기가 따라 바뀌는 것을 확인했습니다.</li></ul>
<h2>검사 결과와 근거 등급</h2><div class="scroll"><table><tr><th>영역</th><th>검사</th><th>등급</th><th>결과</th></tr>{table}</table></div>
<h2>미해결·알려진 한계</h2><ul>
<li><b>:data 결함 발견</b>: base 없는 첫 버전 초안을 ApplyVersion하면 실제 Room이 ChoiceRequired(null, 0)를 돌려줍니다. 소유 모듈을 고치지 않고 :sync 어댑터에서 해당 경우만 같은 첫 버전 기록으로 연결했으며, 상위에 알렸습니다. 원인 분석은 추정 단계입니다.</li>
<li>초기 연결 재시도: 실제 실행의 backoff 관측 여부 = {esc(ready.get('backoff_retry_observed'))}. false면 재시도 경로는 fixture로만 시험된 것입니다.</li>
<li>사용자 선택 화면, 차량 단독 시나리오, 폰 대기 표시, 제품 화면의 토큰 소비는 주장하지 않습니다. 후속 담당 소유입니다.</li>
<li>릴레이 결합 시험의 폰 쪽은 프로토콜 입력용 HTTP 호출이며 실제 폰 앱이 아닙니다.</li>
{notrun}</ul>
<h2>기존 자산 보존</h2><p>상류 core/data 소스 digest가 vehicle-core 기록과 {'일치합니다' if upstream_same else '<b>다릅니다</b>'}. :data는 수정하지 않았습니다. 3100/3101 서버, 다른 에뮬레이터, 기존 DB·계정을 건드리지 않았고 외부 제출·호스팅·결제도 하지 않았습니다.</p>
<h2>재현 명령 (project 폴더에서)</h2><div class="card"><code>bash apps/vehicle-android/vehicle-port/tools/run-port-tests.sh</code><br><code>python3 apps/vehicle-android/designsystem/tools/check_ds_consumption.py</code><br><code>bash apps/vehicle-android/sync/tools/run-sync-tests.sh</code></div>
<h2>시험 APK 해시 (시험 후 제거, 재배포 없음)</h2><div class="scroll"><table>{hashes}</table></div>
<h2>담당과 사용량</h2><div class="card">담당 claude-1 · 제공자 claude · 관측 모델 claude-opus-5-5 · 단계 execute · 시도 {esc(WORKER['attempt'])} · 팀 <code>{esc(WORKER['team'])}</code><br>
native 세션과 입력/출력/cache/reasoning 토큰은 이 작업자가 관측할 수 없어 <b>미확인</b>으로 둡니다(0이 아님). 컨트롤러 worker-accounting이 최종 표를 채웁니다.</div>
<h2>근거 파일</h2><p class="mute">{esc(', '.join(artifacts))}, logs/</p>
</main></body></html>'''
(EVIDENCE / 'platform-report.html').write_text(page)
print(json.dumps({'passed': passed, 'report': 'evidence/product/vehicle-platform/platform-report.html'}, ensure_ascii=False))
sys.exit(0 if passed else 1)
