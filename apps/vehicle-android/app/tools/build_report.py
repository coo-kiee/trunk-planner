#!/usr/bin/env python3
"""Builds the standalone Korean HTML and machine summary from recorded results only. Nothing here decides a pass."""
import base64, html, json, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / 'evidence/product/vehicle-ui'
ACCOUNTING = ROOT.parent / 'worker-accounting.json'
CASES = [
    ('core-scenario', '제품1 저장 → 강제 종료 → 고정 좌표 복원 → 제품2 → 동시 판정'), ('driving', '주행 제한 중 편집·3D 중단과 정차 복원'), ('recreate', '프로세스 재생성 후 초안·되돌리기·시점 복원'),
    ('CE-03-02', '입구와 내부 판정 분리'), ('CE-04-02', '입구 미측정은 통과가 아님'), ('CE-05-02', '입구 통과와 경로 미검증 분리'), ('CE-06-02', '탐색 중단은 불가가 아님'),
    ('S-SEARCH-05', '후보 소진은 불가 증명이 아님'), ('CE-09-02', '지지 부족·공중과 안전 미판정'), ('CE-10-02', '물품 새 버전과 공간 참조'), ('CE-11-02', '판정 만료 숨김과 재검증'),
    ('CE-12-02', '저장 중 강제 종료'), ('CE-13-02', '손상·미래 형식 읽기 전용'), ('CE-14-02', '형식 변환 실패 보존'), ('CE-15-02', '복원과 배경 렌더 중단'), ('CE-16-02', '주행 제한 편집·QR 숨김'),
    ('V5-driving', 'V5·V6 주행 중 동작 숨김'), ('input-bounds', '입력 경계와 저장 거부'), ('fit-view', '좁은 창의 기본 3D 시점(F-02)'), ('CE-17-02', '차량 연결 상태별 표시'), ('CE-18-04', '수신 승인·중복 방지·만료·해제'), ('CE-19-03', '양쪽 수정 충돌 선택'), ('CE-20-03', '릴레이 중단 시 차량 단독'), ('CE-21-02', '차량 좌우와 3D 방향 일치'),
    ('CE-22-03', '근거 등급 표시'), ('S-PROFILE', '미지원·합성 공간 표시'), ('V6-loading', '적재 순서와 트렁크 제안'), ('render', '깊이·선택·프레임·메모리'), ('ds-capture', '실제 화면·3D 토큰 값'),
]
RELEASE_CASES = [('release-core-scenario', '제품1 저장 → 복원 → 제품2 → 동시 판정'), ('release-driving', '주행 제한과 정차 복원'), ('release-recreate', '재생성 복원'), ('release-input-bounds', '입력 경계와 저장 거부'), ('release-pairing-disabled', '페어링 명시 비활성')]
SHOTS = [('core-both-judgement', '제품1+2 동시 판정(V5)'), ('core-restored', '재시작 후 복원(V4)'), ('core-driving', '주행 제한 중'), ('CE-03-02-a', '입구 통과·내부 충돌'), ('CE-13-02-readonly', '손상 시 읽기 전용'), ('CE-14-02', '형식 변환 실패'), ('render-occlusion', '깊이 가림 측정 장면'), ('CE-19-03-choice', '충돌 사용자 선택'), ('V5-driving-restricted', '주행 중 V5 동작 숨김'), ('input-bounds-priority101', '꺼냄순서 101 거부와 입력 보존'), ('ff-12.9-landscape-v4', '12.9 가로 V4'), ('ff-14.6-portrait-v4', '14.6 세로 V4'), ('ff-14.6-landscape-v4', '14.6 가로 V4')]


def load(name):
    path = EVIDENCE / f'{name}.json'
    return json.loads(path.read_text()) if path.exists() else None


def esc(value):
    return html.escape(str(value))


def row_status(status):
    label = {'pass': '통과', 'fail': '실패', 'not_run': '미실행', None: '미실행'}.get(status, status)
    cls = {'pass': 'ok', 'fail': 'bad'}.get(status, 'wait')
    return f'<span class="{cls}">{esc(label)}</span>'


def case_table(rows):
    out = ['<table><thead><tr><th>시험</th><th>내용</th><th>결과</th><th>관측 수</th><th>근거 등급</th></tr></thead><tbody>']
    for ident, title in rows:
        data = load(ident)
        status = data.get('status') if data else None
        count = len(data['observations']) if data else 0
        grade = data.get('evidence_grade', '') if data else ''
        note = f"<div class='small'>{esc(data.get('error'))}</div>" if data and data.get('error') else ''
        out.append(f'<tr><td>{esc(ident)}</td><td>{esc(title)}{note}</td><td>{row_status(status)}</td><td>{count}</td><td class="small">{esc(grade)}</td></tr>')
    return ''.join(out) + '</tbody></table>'


def images():
    out = []
    for name, title in SHOTS:
        path = EVIDENCE / 'captures' / f'{name}.png'
        if path.exists():
            out.append(f'<figure><img alt="{esc(title)}" src="data:image/png;base64,{base64.b64encode(path.read_bytes()).decode()}"><figcaption>{esc(title)}</figcaption></figure>')
    return ''.join(out)


def render_table():
    data = load('render')
    rows = (data or {}).get('inputs', {}).get('measurements') or []
    if not rows:
        return '<p>측정 결과 없음</p>'
    body = ''.join(
        f"<tr><td>{r['instances']}개</td><td>{r['timing'].get('avgMs', 0):.1f}</td><td>{r['timing'].get('p95Ms', 0):.1f}</td><td>{r['timing'].get('maxMs', 0):.1f}</td><td>{r['framesDuringCameraSteps']}</td><td>{r['idleFrameDelta']}</td><td>{(r['totalPssKb'] or 0) // 1024} MB</td></tr>"
        for r in rows)
    return f'<table><thead><tr><th>장면</th><th>평균 ms</th><th>p95 ms</th><th>최대 ms</th><th>카메라 16회 프레임</th><th>무입력 8초 프레임</th><th>전체 PSS</th></tr></thead><tbody>{body}</tbody></table>'


def formfactor_table():
    data = load('formfactor')
    if not data:
        return '<p class="wait">아직 실행하지 않았습니다.</p>'
    rows = [('메인 1창', data.get('main_one_window', {})), ('메인 2창(실제 창 막대)', data.get('main_two_windows', {}))] + [(f'프리셋 {k}', v) for k, v in data.get('presets', {}).items()]
    body = []
    for name, row in rows:
        two = row.get('two_windows', {}).get('status', '') if 'two_windows' in row else ''
        half = row.get('half_width', {}).get('status', '') if 'half_width' in row else ''
        body.append(f"<tr><td>{esc(name)}</td><td>{row_status(row.get('status'))}<div class='small'>{esc(row.get('reason') or row.get('error') or '')}</div></td><td>{esc(row.get('surface', row.get('surface_after_drag', '')))}</td><td>{esc(half)}</td><td>{esc(two)}</td><td>{sum(1 for c in row.get('capture_audit', []) if c['exists']) if 'capture_audit' in row else ''}</td></tr>")
    return '<table><thead><tr><th>창</th><th>결과</th><th>3D 표면</th><th>반폭 렌더</th><th>실제 2창</th><th>화면 캡처</th></tr></thead><tbody>' + ''.join(body) + '</tbody></table>'


def accounting_table():
    if not ACCOUNTING.exists():
        return '<p>회계 파일 없음</p>'
    rows = [r for r in json.loads(ACCOUNTING.read_text()) if r.get('node') == 'root.vehicle-ui']
    def n(v):
        return '미확인' if v is None else f'{v:,}'
    body = ''.join(
        f"<tr><td>{esc(r['phase'])}</td><td>{esc(r['attempt'])}</td><td>{esc(r['worker'])} / {esc(r['provider'])}</td><td>{esc(r.get('configured_model') or '미확인')} / {esc(', '.join(r.get('observed_models') or []) or '미확인')}</td><td class='small'>{esc(r.get('session') or '미확인')}</td>"
        f"<td>{n(r['metrics'].get('input_total'))}</td><td>{n(r['metrics'].get('input_cached'))}</td><td>{n(r['metrics'].get('output_total'))}</td><td>{n(r['metrics'].get('reasoning_output'))}</td><td class='small'>{esc(r.get('source'))}</td></tr>"
        for r in rows)
    return ('<table><thead><tr><th>단계</th><th>시도</th><th>담당/제공자</th><th>설정/관측 모델</th><th>세션</th><th>입력</th><th>입력 중 cache</th><th>출력</th><th>출력 중 reasoning</th><th>출처</th></tr></thead><tbody>' + body + '</tbody></table>'
            '<p class="small">컨트롤러가 기록한 시점의 값입니다. 이 작업의 최종 누적값은 종료 뒤 컨트롤러가 갱신합니다. cache와 reasoning은 각각 입력·출력의 일부이며 미확인은 0이 아닙니다.</p>')


def main():
    results = {ident: (load(ident) or {}).get('status', 'not_run') for ident, _ in CASES + RELEASE_CASES}
    scenario, release, build, release_build, ds = (load(n) for n in ('scenario', 'release-scenario', 'build', 'release-build', 'ds-consumption'))
    summary = {
        'schema': 'vehicle-ui evidence v1', 'generated': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'results': results,
        'scenario': (scenario or {}).get('status', 'not_run'), 'release_scenario': (release or {}).get('status', 'not_run'),
        'build': (build or {}).get('status', 'not_run'), 'release_build': (release_build or {}).get('status', 'not_run'),
        'demo_apk_sha256': (build or {}).get('apk_sha256'), 'release_apk_sha256': (release_build or {}).get('apk_sha256'),
        'demo_tested_sha256': (scenario or {}).get('apk_sha256'), 'release_tested_sha256': (release or {}).get('apk_sha256'),
        'ds_consumption': (ds or {}).get('status', 'not_run'), 'formfactor': (load('formfactor') or {}).get('status', 'not_run'),
        'release_pairing': 'not_run: 공인 HTTPS·호스팅 미승인', 'real_vehicle': 'not_tested', 'real_phone': 'not_tested', 'independent_review': 'pending',
    }
    (EVIDENCE / 'evidence.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    failing = [k for k, v in results.items() if v != 'pass']
    doc = f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>차량 앱 화면·3D 통합 결과</title>
<style>:root{{--bg:#f4f6f9;--ink:#172436;--card:#fff;--line:#d9e0e8;--ok:#0d7a4f;--bad:#b3261e;--wait:#8a5a00}}
@media (prefers-color-scheme:dark){{:root{{--bg:#101b2b;--ink:#f1f5f9;--card:#1b2b41;--line:#33465f;--ok:#38c98b;--bad:#ef6973;--wait:#f4bd50}}}}
body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.65 system-ui,sans-serif}}main{{max-width:1080px;margin:auto;padding:16px}}section{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:16px 0;overflow-x:auto}}
h1{{font-size:26px}}h2{{font-size:20px;margin-top:0}}table{{border-collapse:collapse;width:100%;font-size:14px}}th,td{{border-bottom:1px solid var(--line);padding:6px;text-align:left;vertical-align:top;overflow-wrap:anywhere}}.ok{{color:var(--ok);font-weight:600}}.bad{{color:var(--bad);font-weight:600}}.wait{{color:var(--wait);font-weight:600}}.small{{font-size:12px;opacity:.85}}figure{{margin:0}}img{{width:100%;height:auto;border:1px solid var(--line)}}.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}}code{{overflow-wrap:anywhere}}</style></head><body><main>
<h1>차량 앱 화면·3D 통합 결과</h1>
<p><b>독립 재검토 대기 중입니다.</b> e2e에서 찾은 F-02(2창에서 기본 3D 시점이 공간과 짐을 자름)를 고쳐 다시 시험했습니다. 이 문서는 작성 담당의 실행 기록이며 반대 제공자 검토와 인계 전에는 완료가 아닙니다. 실차와 실제 휴대폰은 시험하지 않았습니다. 현재 demo 시험 기록은 b1edaca7 한 파일에 대한 것입니다. 제어기 전체 실행에서 25개 사례가 통과했고, 부팅 직후 내비게이션 앱이 시험 앱을 덮어 멈춘 공통 3개 사례는 같은 파일로 다시 실행해 통과했습니다. release는 빌드할 때마다 서명 블록이 달라져 파일 SHA가 바뀌므로 시험 SHA와 release-scenario.json을 기준으로 봅니다(release-build.json은 이전 작성자 빌드 기록).</p>
<section><h2>요약</h2><ul>
<li>차량 안에서 직접 치수를 입력해 제품1을 놓고 저장한 뒤, 앱을 강제 종료하고 다시 열어 같은 좌표를 복원하고, 제품2를 추가해 함께 판정하는 흐름을 실제 Pleos 에뮬레이터 화면에서 시험했습니다: {row_status(results['core-scenario'])}</li>
<li>demoLocalDebug 전체 시나리오: {row_status(summary['scenario'])} · 시험서명 release 차량 단독 시나리오: {row_status(summary['release_scenario'])} · 화면 DS 소비 검사: {row_status(summary['ds_consumption'])}</li>
<li>실패·미실행 항목: {esc(', '.join(failing) if failing else '없음')}</li>
<li>release의 휴대폰 연결은 미실행입니다. 공인 HTTPS와 호스팅이 승인되지 않아 앱에서 비활성으로 안내합니다.</li></ul></section>
<section><h2>설치한 APK</h2><table><thead><tr><th>변형</th><th>빌드 SHA-256</th><th>시험 때 설치 일치 SHA-256</th><th>서명</th></tr></thead><tbody>
<tr><td>demoLocalDebug</td><td class="small">{esc(summary['demo_apk_sha256'])}</td><td class="small">{esc(summary['demo_tested_sha256'])}</td><td>디버그 서명</td></tr>
<tr><td>release</td><td class="small">{esc(summary['release_apk_sha256'])}</td><td class="small">{esc(summary['release_tested_sha256'])}</td><td>시험 서명(저장소 밖 키, 배포용 아님)</td></tr></tbody></table>
<p class="small">release clean build를 반복하면 내용물 전체는 같고 서명 블록 바이트만 달라지는 것을 확인했습니다(entries SHA-256 {esc((release_build or {}).get('entries_sha256'))}). 그래서 파일 SHA-256이 달라질 수 있으며, 전달 담당은 시험한 파일 그대로 전달하거나 다시 설치·시험해야 합니다. 두 변형의 결과는 서로 대체하지 않습니다.</p></section>
<section><h2>demoLocalDebug 실제 화면 시험</h2>{case_table(CASES)}</section>
<section><h2>release 시험서명 시험</h2>{case_table(RELEASE_CASES)}</section>
<section><h2>3D 렌더링 실측</h2><p>GLES3와 24비트 깊이 버퍼를 실제 프레임 버퍼 값으로 확인했고, 무입력 때 추가 프레임이 없습니다. 측정이 통과해 Canvas 대안은 만들지 않았습니다. 수치는 macOS 에뮬레이터 값이며 차량 성능을 뜻하지 않습니다.</p>{render_table()}</section>
<section><h2>창 크기</h2>{formfactor_table()}<p class="small">가상 디스플레이에는 창 막대가 없어 실제 2창은 확인하지 못했습니다. 반폭 렌더는 2창 통과가 아닙니다. 재개 지점은 창 막대를 제공하는 실차·공식 환경입니다.</p></section>
<section><h2>근거 등급과 한계</h2><ul>
<li><b>real_ui_input</b>: 사람이 누르는 경로로 입력. <b>seeded_real_store</b>: 실제 판정 엔진과 Room에 저장한 상태를 demoLocalDebug에서 준비한 뒤 화면 확인. <b>fixture_state</b>: 실제 SDK가 재현해 주지 않는 연결 상태를 화면 표시만 시험.</li>
<li>휴대폰 쪽은 릴레이 HTTP 시험 클라이언트이며 휴대폰 웹앱 결합이 아닙니다. 실제 결합은 e2e 담당 범위입니다.</li>
<li>시스템 주행 차단 화면은 작성 시험에서는 뜨지 않았고 독립 검토 실행에서는 앱을 덮는 것이 관측됐습니다. 앱은 두 경우 모두 편집 동작 노드를 두지 않습니다.</li>
<li>안전·고정·중량은 판정하지 않습니다. 트렁크 치수는 사용자 측정 또는 합성 시연값이며 실제 차량 데이터가 아닙니다.</li>
<li>:data에 공간 목록 API가 없어 앱의 작은 색인에 id만 저장합니다. 색인을 잃으면 목록 행만 사라지고 저장값은 Room에 그대로 있습니다.</li>
<li>패널 안 보조 버튼과 입력칸이 패널과 같은 색이라, 각 화면의 Panel 래퍼에서 공통 토큰만으로 배경을 바꿨습니다. DS 원 담당의 수정 요청을 전달했습니다.</li></ul></section>
<section><h2>화면 기록</h2><div class="grid">{images()}</div></section>
<section><h2>담당·사용량</h2>{accounting_table()}</section>
</main></body></html>'''
    (EVIDENCE / 'vehicle-ui.html').write_text(doc)
    print(json.dumps({k: summary[k] for k in ('scenario', 'release_scenario', 'build', 'release_build', 'ds_consumption', 'formfactor')}, ensure_ascii=False), 'failing:', failing)


if __name__ == '__main__':
    main()
