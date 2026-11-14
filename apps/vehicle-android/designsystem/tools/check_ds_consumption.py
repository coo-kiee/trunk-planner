#!/usr/bin/env python3
"""DS consumption check. A pass needs both layers: source rules, then pixels and sizes that follow an injected theme value.

Rejected on purpose: import-only use, a literal copy of a token, a component that skips color, spacing or type, and a smoke
that does not render the component. Product V0~V7/GL consumption is vehicle-ui's own check; this is not a substitute for it.
"""
import glob, json, re, subprocess, sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'apps/vehicle-android/vehicle-port/tools'))
from emu_lib import SERIAL, WORKER, EmulatorBlocked, LogUnavailable, OwnEmulator, adb, gradle, logcat, sha256, write_json  # noqa: E402

MODULE = ROOT / 'apps/vehicle-android/designsystem'
SOURCES = MODULE / 'src/main/java/ai/cargo/designsystem'
SMOKE = MODULE / 'src/androidTest/java/ai/cargo/designsystem/DsConsumptionSmokeTest.kt'
TOKENS = ROOT / 'packages/design-tokens/kotlin/ai/cargo/tokens/CargoTokens.kt'
COMPONENTS = {'CargoButton': 'CargoButton.kt', 'CargoField': 'CargoField.kt', 'CargoStatus': 'CargoStatus.kt', 'CargoPanel': 'CargoPanel.kt'}
COLOR = {'background', 'surface', 'text', 'accent', 'clear', 'conflict', 'uncertain', 'unverified', 'stale', 'selected', 'item'}
SPACING = {'spacingSmall', 'spacingMedium', 'spacingLarge'}
TYPE = {'typeBody', 'typeTitle'}
LITERAL = re.compile(r'Color\(0x|Color\.[A-Z][a-z]+|\b\d+(\.\d+)?\.(dp|sp)\b|#[0-9a-fA-F]{6}')


def strip_comments(text):
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)


def theme_fields(code):
    return set(re.findall(r'\btheme\.(\w+)', code))


def component_problems(name, text, smoke):
    """Source rules for one component file. Returned empty only when it really reads all three token families."""
    code = strip_comments(text)
    fields = theme_fields(code)
    problems = []
    if 'CargoTheme.values' not in code:
        problems.append(f'{name}: does not read CargoTheme.values')
    if name == 'CargoStatus' and 'kind.color()' in code:
        fields |= COLOR & {'clear', 'conflict', 'uncertain', 'unverified', 'stale'}
    if not fields & COLOR:
        problems.append(f'{name}: no color token call')
    if not fields & SPACING:
        problems.append(f'{name}: no spacing token call')
    if not fields & TYPE:
        problems.append(f'{name}: no type token call')
    if LITERAL.search(code):
        problems.append(f'{name}: literal bypasses tokens')
    if not re.search(r'\b' + name + r'\(', strip_comments(smoke)):
        problems.append(f'{name}: not rendered by smoke')
    return problems


def theme_problems(theme, tokens):
    code = strip_comments(theme)
    names = re.findall(r'const val (\w+)', tokens)
    missing = [n for n in names if f'CargoTokens.{n}' not in code]
    problems = [f'CargoTheme: token {n} not mapped' for n in missing]
    if LITERAL.search(code.replace('.dp', '').replace('.sp', '')):
        problems.append('CargoTheme: literal value')
    return names, problems


def counterexamples(smoke):
    """The rules must reject each known bad form. A clean result is meaningless otherwise."""
    good = (SOURCES / 'CargoButton.kt').read_text()
    cases = {
        'import_only': ('CargoButton', 'import ai.cargo.designsystem.CargoTheme\nfun CargoButton() {}', smoke),
        'literal_color': ('CargoButton', good.replace('theme.accent', 'Color(0xFF5BC8FF)'), smoke),
        'literal_spacing': ('CargoButton', good.replace('theme.spacingMedium', '16.dp'), smoke),
        'missing_type': ('CargoButton', good.replace('fontSize = theme.typeBody', ''), smoke),
        'not_rendered_in_smoke': ('CargoButton', good, smoke.replace('CargoButton(', 'Other(')),
    }
    return {name: bool(component_problems(*args)) for name, args in cases.items()}


def smoke_results():
    cases = []
    for path in sorted(glob.glob(str(MODULE / 'build/outputs/androidTest-results/connected/**/*.xml'), recursive=True)):
        for case in ET.parse(path).getroot().iter('testcase'):
            failure = case.find('failure')
            cases.append({'name': case.get('name'), 'status': 'failed' if failure is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                          'failure': (failure.text or '')[:600] if failure is not None else None})
    return cases


def main():
    smoke = SMOKE.read_text()
    problems = []
    per_component = {}
    for name, file in COMPONENTS.items():
        text = (SOURCES / file).read_text()
        per_component[name] = sorted(theme_fields(strip_comments(text)))
        problems += component_problems(name, text, smoke)
    names, theme_issue = theme_problems((SOURCES / 'CargoTheme.kt').read_text(), TOKENS.read_text())
    problems += theme_issue
    rejected = counterexamples(smoke)
    if not all(rejected.values()):
        problems.append(f'rules accept a counterexample: {rejected}')

    runtime = {'status': 'not_run'}
    observations = []
    try:
        with OwnEmulator():
            adb('logcat', '-c')
            run = gradle(ROOT / 'apps/vehicle-android', ':designsystem:clean', ':designsystem:connectedDebugAndroidTest', log_name='ds-connected-gradle.log')
            tests = smoke_results()
            log_problem = None
            try:
                observations = [json.loads(line) for line in logcat('DSSMOKE').splitlines() if line.startswith('{')]
            except LogUnavailable as error:
                log_problem = str(error)
                problems.append(log_problem)
            runtime = {'status': 'passed' if run['exit'] == 0 and tests and all(t['status'] == 'passed' for t in tests) else 'failed', 'command': run, 'tests': tests, 'log_problem': log_problem}
    except (EmulatorBlocked, subprocess.TimeoutExpired) as error:
        runtime = {'status': 'not_run', 'reason': str(error)}

    followed = {(o['component'], o['token']) for o in observations if o['observed'] == 'followed'}
    required = {
        'CargoButton': ['colorAccent', 'colorSurface', 'spacingLarge', 'typeBody'],
        'CargoField': ['colorSurface', 'spacingMedium', 'typeBody'],
        'CargoStatus': ['colorClear', 'colorConflict', 'colorUncertain', 'colorUnverified', 'colorStale', 'spacingSmall'],
        'CargoPanel': ['colorSurface', 'typeTitle', 'spacingLarge'],
    }
    missing = [f'{c}:{t}' for c, tokens in required.items() for t in tokens if (c, t) not in followed]
    if runtime['status'] == 'passed' and missing:
        problems.append(f'injected value did not reach pixels for {missing}')
    passed = not problems and runtime['status'] == 'passed'
    write_json('ds.json', {
        'schema': 'vehicle-platform evidence v1', **WORKER, 'argv': ['python3', 'apps/vehicle-android/designsystem/tools/check_ds_consumption.py'], 'cwd': 'project', 'passed': passed, 'grade': 'emulator',
        'serial': SERIAL, 'tokens_file_sha256': sha256(TOKENS), 'tokens': names, 'component_token_calls': per_component, 'problems': problems, 'counterexamples_rejected': rejected,
        'runtime': runtime, 'injected_value_observations': observations, 'required_followed': required,
        'not_consumed_by_ds_components': ['colorSelected', 'colorItem'],
        'boundary': '토큰→컴포넌트 렌더 경로만 증명한다. 제품 V0~V7·GL 소비는 vehicle-ui/e2e가 별도로 증명해야 한다. colorSelected/colorItem은 CargoThemeValues로 제공하며 3D 소비 책임은 render-gl이다.',
        'consumer_contract': ':designsystem만 generated token srcDir를 포함하고 소비자는 이 모듈로만 토큰을 받는다. 같은 srcDir를 또 넣으면 duplicate class가 된다.'})
    print(json.dumps({'passed': passed, 'problems': problems, 'runtime': runtime['status'], 'observations': len(observations), 'rejected': rejected}, ensure_ascii=False))
    for t in runtime.get('tests', []):
        if t['status'] != 'passed':
            print('FAILED', t['name'], (t['failure'] or '')[:700])
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
