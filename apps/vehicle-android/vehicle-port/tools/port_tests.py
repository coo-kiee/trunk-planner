#!/usr/bin/env python3
"""Real port check. Grades stay separate: JVM results are fixture, emulator results are emulator. Nothing is real-vehicle."""
import glob, json, re, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from emu_lib import AVD, ENV, EVIDENCE, LOGS, ROOT, SERIAL, WORKER, EmulatorBlocked, OwnEmulator, VehicleConsole, adb, gradle, sha256, write_json  # noqa: E402

MODULE = ROOT / 'apps/vehicle-android/vehicle-port'
HARNESS = MODULE / 'harness'
FULL, NOPERM = 'ai.cargo.portharness', 'ai.cargo.portharness.noperm'
ACTIVITY = 'ai.cargo.portharness.HarnessActivity'
APK = {FULL: HARNESS / 'app/build/outputs/apk/full/debug/harness-app-full-debug.apk',
       NOPERM: HARNESS / 'app/build/outputs/apk/noperm/debug/harness-app-noperm-debug.apk'}
DOOR_PERMISSION = 'pleos.car.permission.CAR_DOORS'
BINDING_PERMISSION = 'VEHICLE_BINDING'
TAG = 'PORTHARNESS'
steps = []


def step(name, ok, grade, **detail):
    steps.append({'name': name, 'passed': bool(ok), 'grade': grade, **detail})
    print(('PASS ' if ok else 'FAIL ') + name, flush=True)
    return ok


def harness_log():
    # A stalled dump reads as nothing seen yet: the caller's own deadline then fails the step instead of crashing the run.
    try:
        return adb('logcat', '-d', '-v', 'brief', '-s', f'{TAG}:I', 'AndroidRuntime:E', timeout=30).stdout
    except subprocess.TimeoutExpired:
        return ''


def wait_log(pattern, timeout=25):
    """Event driven: returns as soon as the line is observed, or the log so far on timeout."""
    deadline = time.time() + timeout
    rule = re.compile(pattern)
    text = ''
    while time.time() < deadline:
        text = harness_log()
        found = rule.search(text)
        if found:
            return found.group(0)
        time.sleep(0.5)
    return None


def clear_log():
    adb('logcat', '-c')


def send(package, cmd=None):
    args = ['shell', 'am', 'start', '-n', f'{package}/{ACTIVITY}']
    if cmd:
        args += ['--es', 'cmd', cmd]
    return adb(*args).stdout


def foreground():
    text = adb('shell', 'dumpsys', 'activity', 'activities').stdout
    found = re.search(r'topResumedActivity=.*?\{[^}]*\s(\S+/\S+)', text) or re.search(r'mResumedActivity.*?\s(\S+/\S+)', text)
    return found.group(1) if found else None


def aapt_permissions(apk):
    tools = sorted(glob.glob(str(Path(ENV['ANDROID_HOME']) / 'build-tools/*/aapt2')))
    out = subprocess.run([tools[-1], 'dump', 'permissions', str(apk)], capture_output=True, text=True, timeout=60).stdout
    return sorted(re.findall(r"name='?([\w.]+)", out))


def grant_state(package):
    text = adb('shell', 'dumpsys', 'package', package).stdout
    found = re.search(re.escape(DOOR_PERMISSION) + r': granted=(\w+)', text)
    return found.group(1) if found else 'absent'


def protection_level():
    text = adb('shell', 'dumpsys', 'package', 'permissions', timeout=60).stdout
    found = re.search(r'Permission \[' + re.escape(DOOR_PERMISSION) + r'\].*?prot=(\S+)', text, re.S)
    return found.group(1) if found else None


def unit_results():
    tests = []
    for path in sorted(glob.glob(str(MODULE / 'build/test-results/testDebugUnitTest/*.xml'))):
        for case in ET.parse(path).getroot().iter('testcase'):
            tests.append({'class': case.get('classname').split('.')[-1], 'name': case.get('name'),
                          'status': 'failed' if case.find('failure') is not None else 'passed'})
    return tests


def static_checks():
    script = MODULE / 'tools/check_port_static.py'
    result = subprocess.run([sys.executable, str(script)], capture_output=True, text=True, timeout=60)
    step('공개 시그니처 SDK 타입 차단·금지 API·manifest 정적 검사', result.returncode == 0, 'static', output=json.loads(result.stdout or '{}'))


def build():
    outcome = gradle(HARNESS, 'clean', ':vehicle-port:testDebugUnitTest', ':harness-app:assembleFullDebug', ':harness-app:assembleNopermDebug', log_name='port-gradle.log')
    step('독립 harness clean build 및 JVM fixture', outcome['exit'] == 0, 'fixture', command=outcome)
    tests = unit_results()
    failed = [t for t in tests if t['status'] != 'passed']
    step('port JVM fixture 전부 통과', tests and not failed, 'fixture', count=len(tests), failed=failed)
    return outcome['exit'] == 0, tests


def manifest_checks():
    full, noperm = aapt_permissions(APK[FULL]), aapt_permissions(APK[NOPERM])
    step('full APK 병합 manifest: CAR_DOORS+VEHICLE_BINDING, Navi/Speed 없음', DOOR_PERMISSION in full and any(BINDING_PERMISSION in p for p in full) and not any('NAVI_ROUTE' in p or 'CAR_SPEED' in p for p in full), 'artifact', permissions=full)
    step('noperm APK 병합 manifest: CAR_DOORS만 제거', DOOR_PERMISSION not in noperm and any(BINDING_PERMISSION in p for p in noperm), 'artifact', permissions=noperm)


def scenario_full(console):
    grade = 'emulator'
    clear_log()
    send(FULL)
    before = wait_log(r'beforeInitialize \| .*')
    step('초기화 전 실제 SDK 호출 경계 분류', before and 'NotConnected' in before, grade, observed=before)
    available = wait_log(r'door \| DoorState\(availability=Available\(minValue=0, maxValue=100\).*')
    step('capability REAR 0..100 → Available (고정 sleep 없음)', available, grade, observed=available)
    ready_path = re.findall(r'door \| (DoorState\(availability=NotReady[^\n]*)', harness_log())
    step('초기 연결 경로 실측 기록(NotReady 시도 횟수)', True, grade, observed=ready_path, backoff_retry_observed=any('attempt=' in line and 'attempt=0' not in line for line in ready_path))
    baseline = wait_log(r'door \| DoorState\(availability=Available.*position=Closed.*')
    step('초기값은 기준선이고 제안이 없음', baseline and 'suggestion |' not in harness_log(), grade, observed=baseline)
    ux = wait_log(r'ux \| Known\(requiresDistractionOptimization=false.*editing=true qr=true renderStop=false')
    step('정차 UX 상태', ux, grade, observed=ux)
    navi = wait_log(r'navi \| .*')
    step('Navi 도착 기본꺼짐', navi and 'Disabled(reason=NOT_OBSERVED)' in navi, grade, observed=navi)
    top = foreground()
    step('runtime 권한 대화상자 없음', top and 'portharness' in top and 'grantpermissions' not in top.lower(), grade, foreground=top)

    clear_log()
    http = console.put('/vehicle/body?trunk=1')
    open_line = wait_log(r'suggestion \| SHOW_LOADING_CHECKLIST')
    position = wait_log(r'door \| .*position=Open.*')
    step('콘솔 트렁크 열림 → Open + 체크리스트 제안', http == '200' and open_line and position, grade, console_http=http, observed=[open_line, position])
    clear_log()
    http = console.put('/vehicle/body?trunk=0')
    close_line = wait_log(r'suggestion \| OFFER_SAVE_CONFIRMATION')
    step('콘솔 트렁크 닫힘 → 저장확인 제안', http == '200' and close_line, grade, console_http=http, observed=close_line)

    clear_log()
    gear = console.put('/vehicle/gear?gear=Drive')
    speed = console.put('/vehicle/controls?speed=40&accelerator=30&brake=0')
    drive = wait_log(r'ux \| Known\(requiresDistractionOptimization=true, rawRestrictionMask=\d+\).*editing=false qr=false renderStop=true', timeout=40)
    step('CE-16-01 Drive 실제 callback → 제한 state·render-stop', gear == '200' and speed == '200' and drive, grade, console_http=[gear, speed], observed=drive)
    blocked = foreground()
    clear_log()
    speed = console.put('/vehicle/controls?speed=0&accelerator=0&brake=0')
    gear = console.put('/vehicle/gear?gear=Park')
    park = wait_log(r'ux \| Known\(requiresDistractionOptimization=false, rawRestrictionMask=\d+\).*editing=true qr=true renderStop=false', timeout=40)
    step('CE-16-01 Park 실제 callback → 제한 해제 state', park, grade, console_http=[speed, gear], observed=park, foreground_during_drive=blocked)
    time.sleep(2)

    clear_log()
    send(FULL, 'row3')
    capability = wait_log(r'row3\.capability \| .*')
    read = wait_log(r'row3\.read \| .*')
    step('CE-17-01 capability 없음: ROW_3 present=false', capability and 'present=false' in capability, grade, observed=capability)
    step('CE-17-01 영역 거부: ROW_3 실제 read → AreaRejected', read and 'AreaRejected' in read, grade, observed=read)

    clear_log()
    send(FULL, 'afterRelease')
    after = wait_log(r'afterRelease \| .*')
    step('CE-17-01 해제 후 실제 호출 분류', after and 'CallAfterRelease' in after, grade, observed=after)

    clear_log()
    send(FULL, 'release')
    released = wait_log(r'door \| DoorState\(availability=Released.*')
    ux_released = wait_log(r'ux \| Unavailable\(reason=RELEASED.*editing=false qr=false renderStop=true')
    step('port release: door Released, UX fail-closed', released and ux_released, grade, observed=[released, ux_released])
    clear_log()
    http = console.put('/vehicle/body?trunk=1')
    time.sleep(4)
    late = harness_log()
    # 200 is required: without a real trunk change this silence would prove nothing.
    step('해제 후 트렁크 변화는 상태·제안을 만들지 않음', http == '200' and 'suggestion |' not in late and 'door |' not in late, grade, console_http=http, observed_lines=late.count(TAG))
    console.put('/vehicle/body?trunk=0')
    time.sleep(3)

    clear_log()
    send(FULL, 'restart')
    again = wait_log(r'door \| DoorState\(availability=Available.*')
    step('재시작 시 새 세션 Available', again, grade, observed=again)
    wait_log(r'door \| .*position=Closed.*')
    clear_log()
    http = console.put('/vehicle/body?trunk=1')
    reopened = wait_log(r'suggestion \| SHOW_LOADING_CHECKLIST')
    step('재시작 후 새 세션 listener 동작', reopened, grade, observed=reopened)
    console.put('/vehicle/body?trunk=0')
    wait_log(r'suggestion \| OFFER_SAVE_CONFIRMATION')
    fatal = 'FATAL EXCEPTION' in adb('logcat', '-d', '-b', 'crash').stdout
    step('full 시나리오 동안 프로세스 치명 오류 없음', not fatal, grade)
    adb('shell', 'am', 'force-stop', FULL)


def scenario_noperm():
    grade = 'emulator'
    adb('logcat', '-b', 'crash', '-c')
    clear_log()
    send(NOPERM)
    before = wait_log(r'beforeInitialize \| .*')
    step('CE-17-01 권한 누락: 초기화 전 호출 SecurityException', before and 'PermissionMissing' in before and 'SecurityException' in before, grade, observed=before)
    door = wait_log(r'door \| DoorState\(availability=PermissionMissing.*')
    step('CE-17-01 권한 누락: port PermissionMissing', door, grade, observed=door)
    top = foreground()
    step('권한 누락에도 runtime 대화상자 없음·앱 유지', top and 'portharness' in top and 'grantpermissions' not in top.lower(), grade, foreground=top)
    ux = wait_log(r'ux \| .*')
    fatal = 'FATAL EXCEPTION' in adb('logcat', '-d', '-b', 'crash').stdout
    step('권한 누락에도 프로세스 치명 오류 없음', not fatal, grade, ux=ux)
    adb('shell', 'am', 'force-stop', NOPERM)


NOT_RUN = [
    {'case': 'capability 콜백 null', 'status': 'fixture_only', 'reason': '실제 SDK가 null을 주도록 유발하는 공개 경로 없음'},
    {'case': '실행 중 서비스 단절 후 재연결', 'status': 'fixture_only', 'reason': 'hmg_car_service 금지·공개 유발 경로 없음'},
    {'case': '실행 중 listener 오류(권한 철회 등) 뒤 제안 차단', 'status': 'fixture_only', 'reason': 'normal 권한은 실행 중 철회를 공개 경로로 유발할 수 없음. DoorControllerTest와 검토자 반례로 시험'},
    {'case': 'SDK 호출·상태 발행 중 재진입 release/start (UX·Door)', 'status': 'fixture_only', 'reason': '실제 manager·SDK에서 관측하지 못했고 유발 경로도 없음'},
    {'case': 'unregister 호출 중 재진입 callback', 'status': 'fixture_only', 'reason': '실제 SDK에서 관측하지 못했고 유발 경로도 없음. 지연 보장이 문서로 확인되지 않아 fixture로 경계를 유지'},
    {'case': '등록 호출 중 inline onFailed·연결상실', 'status': 'fixture_only', 'reason': '실제 SDK에서 이런 동작을 관측하지 못했고 유발 경로도 없음. 지연 보장이 문서로 확인되지 않아 fixture로 경계를 유지'},
    {'case': 'CarUxRestrictions null/Car null/manager null', 'status': 'fixture_only', 'reason': '실제 유발 경로 없음'},
    {'case': 'initialize 직후 NotConnected backoff', 'status': 'see_step', 'reason': "'초기 연결 경로 실측 기록' 단계의 backoff_retry_observed가 false면 재시도는 fixture로만 시험된 것이다"},
    {'case': '실제 주행 차단 화면·편집/QR 숨김', 'status': 'not_run_here', 'reason': 'vehicle-ui CE-16-02 소유'},
    {'case': '실차', 'status': 'not_run', 'reason': '실차 미시험'},
]


def main():
    started = time.time()
    LOGS.mkdir(parents=True, exist_ok=True)
    static_checks()
    built, unit = build()
    blocked = None
    if built:
        manifest_checks()
        try:
            with OwnEmulator():
                for package, apk in APK.items():
                    adb('uninstall', package)
                    out = adb('install', '-t', str(apk), timeout=180)
                    step(f'{package} 설치', 'Success' in out.stdout, 'emulator')
                full_grant, none_grant, level = grant_state(FULL), grant_state(NOPERM), protection_level()
                step('dumpsys: full granted=true, noperm 부재', full_grant == 'true' and none_grant == 'absent', 'emulator', full=full_grant, noperm=none_grant, protection_level=level)
                step('CAR_DOORS protectionLevel normal 관측', level == 'normal', 'emulator', protection_level=level)
                with VehicleConsole() as console:
                    scenario_full(console)
                scenario_noperm()
                (LOGS / 'port-harness-final.log').write_text(harness_log())
                for package in APK:
                    adb('uninstall', package)
        except (EmulatorBlocked, subprocess.TimeoutExpired) as error:
            blocked = str(error)
            step('자기 에뮬레이터 사용 가능', False, 'emulator', cause=blocked)
    failed = [s for s in steps if not s['passed']]
    artifacts = {p: {'sha256': sha256(a), 'path': str(a.relative_to(ROOT))} for p, a in APK.items() if a.exists()}
    common = {'schema': 'vehicle-platform evidence v1', **WORKER, 'argv': ['bash', 'apps/vehicle-android/vehicle-port/tools/run-port-tests.sh'], 'cwd': 'project',
              'vehicle_sdk': 'ai.pleos.playground:Vehicle:2.3.2', 'avd': AVD, 'serial': SERIAL, 'artifacts': artifacts,
              'real_vehicle': 'not_tested', 'mock_policy': 'fake는 src/test에만 존재하며 emulator 등급 결과에 쓰이지 않는다.'}
    def pick(*prefixes):
        return [s for s in steps if any(s['name'].startswith(p) for p in prefixes)]
    write_json('sdk.json', {**common, 'passed': not failed, 'blocked': blocked, 'seconds': round(time.time() - started), 'steps': steps,
                            'unit_fixture_count': len(unit), 'unit_fixture': unit, 'not_run': NOT_RUN,
                            'boundary': '자기 독립 harness의 port typed state·SDK effect 시험. 제품 :app/화면 시험 아님.'})
    write_json('CE-16-01.json', {**common, 'case': 'CE-16-01', 'responsibility': '실제 CarUxRestrictions callback → typed restriction state/render-stop', 'expected': 'Drive: requiresDO=true → editing/qr false, renderStop true; Park: 복귀', 'actual': pick('CE-16-01'), 'passed': bool(pick('CE-16-01')) and all(s['passed'] for s in pick('CE-16-01')), 'grade': 'emulator', 'ui_owner': 'vehicle-ui CE-16-02가 화면 숨김을 시험한다'})
    ce17 = pick('CE-17-01', 'capability REAR', '독립 harness')
    write_json('CE-17-01.json', {**common, 'case': 'CE-17-01', 'responsibility': 'capability/권한누락/null/초기연결/해제 port 상태', 'expected': '서로 다른 typed 상태, mock 성공 없음', 'actual': ce17, 'fixture_tests': [t for t in unit if t['class'] in ('DoorControllerTest', 'SdkFailureTest', 'UxControllerTest')], 'not_run': NOT_RUN, 'passed': all(s['passed'] for s in ce17) and bool(ce17), 'grade': 'emulator+fixture(각 항목별 표기)'})
    print(json.dumps({'steps': len(steps), 'failed': [s['name'] for s in failed], 'blocked': blocked}, ensure_ascii=False))
    return 0 if not failed and not blocked else 1


if __name__ == '__main__':
    sys.exit(main())
