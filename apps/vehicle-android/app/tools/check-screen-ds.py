#!/usr/bin/env python3
"""Fails unless the real screens consume the design system: real call sites in source and real values on the display.

Source side: component call sites and token reads per screen, Status kinds used by the judgement mapper, no literal
colors or foreign UI kits, renderer colors only from theme tokens. Runtime side: evidence/product/vehicle-ui/ds-runtime-capture.json
from run-scenario, which holds real pixels and GL values with product tokens and with a test override. Without that file
this check fails: no static result stands in for an observed one.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
APP = ROOT / 'apps/vehicle-android'
EVIDENCE = ROOT / 'evidence/product/vehicle-ui'
A = 'app/src/main/java/ai/cargo/planner'
E = 'feature-editor/src/main/java/ai/cargo/feature/editor'
SCREENS = {
    'V0': ([f'{A}/HomeScreen.kt'], {'CargoButton', 'CargoStatus', 'Panel'}, {'CargoField': '선택·이동만 있어 입력 칸이 없는 화면'}),
    'V1': (['feature-profile/src/main/java/ai/cargo/feature/profile/ProfileScreen.kt'], {'CargoButton', 'CargoField', 'CargoStatus', 'Panel'}, {}),
    'V2': (['feature-library/src/main/java/ai/cargo/feature/library/LibraryScreen.kt'], {'CargoButton', 'CargoField', 'CargoStatus', 'Panel'}, {}),
    'V3': (['feature-pairing/src/main/java/ai/cargo/feature/pairing/PairingScreen.kt'], {'CargoButton', 'CargoStatus', 'Panel'}, {'CargoField': 'QR과 선택 버튼만 있어 입력 칸이 없는 화면'}),
    'V4': ([f'{E}/EditorScreen.kt', f'{E}/EditorControls.kt'], {'CargoButton', 'CargoField', 'CargoStatus', 'Panel'}, {}),
    'V5': ([f'{E}/JudgementViews.kt'], {'CargoButton', 'CargoStatus', 'Panel'}, {'CargoField': '판정을 읽는 화면이라 입력 칸이 없음'}),
    'V6': (['feature-loading/src/main/java/ai/cargo/feature/loading/LoadingScreen.kt'], {'CargoButton', 'CargoStatus', 'Panel'}, {'CargoField': '체크리스트 화면이라 입력 칸이 없음'}),
    'V7': ([f'{A}/RecoveryScreen.kt'], {'CargoStatus', 'Panel'}, {'CargoField': '복구 안내만 표시', 'CargoButton': '쓰기 동작을 두지 않는 화면'}),
}
OWN_DIRS = ['app/src/main', 'app/src/release', 'feature-library/src/main', 'feature-profile/src/main', 'feature-editor/src/main', 'feature-loading/src/main', 'feature-pairing/src/main', 'render-gl/src/main']
# The QR symbol is a machine code that scanners read as black on white. It is not part of the app's look.
LITERAL_ALLOWED = {'feature-pairing/src/main/java/ai/cargo/feature/pairing/QrEncoder.kt': 'QR 기호의 흑백은 스캐너용 기계 부호'}
LITERALS = re.compile(r'Color\(0x|Color\.(Red|Blue|Green|Black|White|Gray|Yellow|Cyan|Magenta)\b|0xFF[0-9A-Fa-f]{6}|"#[0-9A-Fa-f]{6}"|parseColor')
FOREIGN = re.compile(r'androidx\.compose\.material')
TOKEN = re.compile(r'\b(?:theme|CargoTheme\.values)\.(background|surface|text|accent|clear|conflict|uncertain|unverified|stale|selected|item|spacingSmall|spacingMedium|spacingLarge|typeBody|typeTitle)\b')


def call_sites(text, name):
    return [i + 1 for i, line in enumerate(text.splitlines()) if re.search(rf'(?<![\w.]){name}\(', line) and 'fun ' not in line and not line.lstrip().startswith(('import', '//', '*'))]


def analyse_screen(sources):
    """Component call lines and token reads. An import alone produces no call line, so it counts for nothing."""
    calls, tokens = {}, {}
    for relative, text in sources.items():
        for component in ('CargoButton', 'CargoField', 'CargoStatus', 'Panel'):
            lines = call_sites(text, component)
            if lines:
                calls.setdefault(component, []).extend(f'{relative}:{n}' for n in lines)
        for i, line in enumerate(text.splitlines()):
            for token in TOKEN.findall(line):
                tokens.setdefault(token, []).append(f'{relative}:{i + 1}')
    return calls, tokens


def self_test():
    """The checker has to reject the shapes it exists to reject, or its pass means nothing."""
    import_only = {'x.kt': 'import ai.cargo.designsystem.CargoButton\nimport ai.cargo.designsystem.CargoStatus\nfun main() {}\n'}
    calls, tokens = analyse_screen(import_only)
    assert not calls and not tokens, 'import-only must yield no consumption'
    assert LITERALS.search('val c = Color(0xFF00FF00)'), 'literal color must be found'
    assert FOREIGN.search('import androidx.compose.material3.Button'), 'foreign kit must be found'
    used = {'y.kt': 'CargoButton("a", {})\nval s = theme.spacingLarge\n'}
    calls, tokens = analyse_screen(used)
    assert calls['CargoButton'] == ['y.kt:1'] and tokens['spacingLarge'] == ['y.kt:2'], 'real use must be found'


def runtime_section(problems):
    path = EVIDENCE / 'ds-runtime-capture.json'
    if not path.exists():
        problems.append('ds-runtime-capture.json 없음: run-scenario --variant demoLocalDebug --only ds-capture 를 먼저 실행해야 함')
        return None
    capture = json.loads(path.read_text())
    tokens = {name[0].lower() + name[1:]: '#' + value.upper() for name, value in re.findall(r'color(\w+): Long = 0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})L', (ROOT / 'packages/design-tokens/kotlin/ai/cargo/tokens/CargoTokens.kt').read_text())}
    override = dict(pair.split('=') for pair in capture['override'].split(','))
    rows, screens_seen = [], set()
    for run, table in (('product', tokens), ('override_run', override)):
        for sample in capture[run]['samples']:
            screens_seen.add(sample['screen'])
            if 'pixel' not in sample:
                continue
            expected = table[sample['token']]
            ok = sample['pixel'] == expected
            rows.append({'run': run, 'screen': sample['screen'], 'tag': sample['tag'], 'component': sample['component'], 'tokenId': sample['token'], 'expected': expected, 'observed': sample['pixel'], 'pass': ok})
            if not ok:
                problems.append(f"{run} {sample['screen']} {sample['tag']}: {sample['pixel']} != {sample['token']} {expected}")
    missing = set(SCREENS) - screens_seen
    if missing:
        problems.append(f'실제 캡처 없는 화면: {sorted(missing)}')
    for kind in ('clear', 'conflict', 'uncertain', 'unverified', 'stale'):
        for screen in ('V4', 'V5'):
            seen = [s for s in capture['product']['samples'] if s['screen'] == screen and s['token'] == kind and s['component'] == 'CargoStatus']
            if not seen:
                problems.append(f'{screen} 판정색 {kind} 실제 캡처 없음')
    gl_rows = []
    for role in ('item', 'selected', 'conflict', 'unverified'):
        for run, table in (('product', tokens), ('override_run', override)):
            observed = capture[run]['gl'][role]['rgba']
            ok = observed == table[role]
            gl_rows.append({'run': run, 'role': role, 'tokenId': role, 'expected': table[role], 'observedFramebuffer': observed, 'pass': ok})
            if not ok:
                problems.append(f'GL {run} {role}: {observed} != {table[role]}')
    if capture['tokens_hash_before'] != capture['tokens_hash_after']:
        problems.append('시험 중 제품 토큰 원본이 바뀜')
    return {'pixel_samples': rows, 'gl_framebuffer': gl_rows, 'override': capture['override'], 'tokens_hash': capture['tokens_hash_after']}


def main():
    self_test()
    problems, screens = [], {}
    for screen, (files, required, exempt) in SCREENS.items():
        sources = {f: (APP / f).read_text() for f in files}
        calls, tokens = analyse_screen(sources)
        for component in required - set(calls):
            problems.append(f'{screen}: {component} 실제 호출 없음')
        screens[screen] = {'files': files, 'component_calls': calls, 'token_reads': tokens, 'not_applicable': exempt}
    # Screens read color through components; spacing and type tokens appear directly in their layouts.
    for token in ('spacingSmall', 'spacingMedium', 'spacingLarge', 'typeBody'):
        if not any(token in s['token_reads'] for s in screens.values()):
            problems.append(f'{token} 토큰 소비 없음')
    # Screens call a one-line module wrapper; it must really hand over to the shared CargoPanel.
    wrappers = {}
    for path in APP.glob('*/src/main/java/**/Panel.kt'):
        relative = str(path.relative_to(APP))
        wrappers[relative] = call_sites(path.read_text(), 'CargoPanel')
        if not wrappers[relative]:
            problems.append(f'{relative}: CargoPanel 호출 없음')
    literal_hits, foreign_hits = [], []
    for directory in OWN_DIRS:
        for path in (APP / directory).rglob('*.kt'):
            relative = str(path.relative_to(APP))
            for i, line in enumerate(path.read_text().splitlines()):
                if FOREIGN.search(line):
                    foreign_hits.append(f'{relative}:{i + 1}')
                if LITERALS.search(line) and relative not in LITERAL_ALLOWED:
                    literal_hits.append(f'{relative}:{i + 1}: {line.strip()[:80]}')
    problems += [f'literal 색: {h}' for h in literal_hits] + [f'외부 UI kit: {h}' for h in foreign_hits]
    presenter = (APP / E / 'JudgementPresenter.kt').read_text()
    kinds = {kind: [i + 1 for i, line in enumerate(presenter.splitlines()) if f'CargoStatusKind.{kind}' in line] for kind in ('CLEAR', 'CONFLICT', 'UNCERTAIN', 'UNVERIFIED', 'STALE')}
    problems += [f'판정 매핑에 {k} 없음' for k, v in kinds.items() if not v]
    renderer = (APP / 'render-gl/src/main/java/ai/cargo/render/RenderPalette.kt').read_text()
    mapping = re.findall(r'(\w+) = theme\.(\w+)\.rgba\(\)', renderer)
    for role, token in (('item', 'item'), ('selected', 'selected'), ('conflict', 'conflict'), ('unverified', 'unverified')):
        if (role, token) not in mapping:
            problems.append(f'renderer {role}가 토큰 {token}에서 오지 않음')
    if re.search(r'val \w+: Rgba\s*=', renderer):
        problems.append('RenderPalette에 기본 색 값이 있음')
    runtime = runtime_section(problems)
    result = {
        'schema': 'vehicle-ui screen DS consumption v1', 'status': 'fail' if problems else 'pass', 'problems': problems,
        'self_test': 'pass (import-only rejected, literal found, foreign kit found, real use found)',
        'screens': screens, 'judgement_mapping_lines': kinds, 'renderer_palette_mapping': mapping, 'panel_wrappers_to_CargoPanel': wrappers,
        'literal_allowlist': LITERAL_ALLOWED, 'runtime': runtime, 'grade': 'source + emulator real pixels and GL framebuffer',
        'real_vehicle': 'not_tested',
    }
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'ds-consumption.json').write_text(json.dumps(result, ensure_ascii=False, indent=1))
    print(json.dumps({'status': result['status'], 'problems': problems[:20]}, ensure_ascii=False, indent=1))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
