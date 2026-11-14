#!/usr/bin/env python3
"""Product checks on real windows: main display one and two windows, and the four official console presets.

Grades stay apart. Main display two windows use the real window bar. The virtual presets have no window bar on this image,
so `am task resize` is recorded only as half-width rendering and never as a passed second window. Resolution alone proves
nothing about a physical form factor, and this has not run on a real vehicle.
"""
import hashlib, json, os, re, signal, socket, struct, subprocess, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ui_driver import CAPTURES, EVIDENCE, LOGS, ROOT, SERIAL, StepFailed, Ui, adb, emu_lib, now_iso  # noqa: E402
import flows  # noqa: E402
from core_flow import wait_axes  # noqa: E402

PACKAGE = 'ai.cargo.planner.demolocal'
APK = ROOT / 'apps/vehicle-android/app/build/outputs/apk/demoLocalDebug/app-demoLocalDebug.apk'
AVD, CONSOLE, GRPC = emu_lib.AVD, emu_lib.CONSOLE, emu_lib.GRPC
SDK_ENV = ROOT / 'tools/sdk-probe/env'
STATE = Path(emu_lib.ENV['PRODUCT_ENV'])
BRIDGE_URL = 'http://127.0.0.1:8096/api/window-policy/display'
PRESETS = {  # surface sizes are what the probe measured and the plan names; offsets are the console's own dropdown rows
    '12.9-portrait': (156, 54, 628, 759, (628, 855)),
    '12.9-landscape': (156, 105, 1274, 759, (1274, 855)),
    '14.6-portrait': (105, 54, 840, 1065, (840, 1185)),
    '14.6-landscape': (105, 105, 1700, 1065, (1700, 1185)),
}
SETTINGS = 'com.android.car.settings/.Settings_Launcher_Homepage'


def wait_boot():
    for _ in range(90):
        if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1':
            time.sleep(15)
            return True
        time.sleep(5)
    return False


class WindowedEmulator:
    """The bridge needs a windowed emulator and a tokenless local gRPC. Only this product's own instance is restarted."""

    def __enter__(self):
        if emu_lib.port_open(CONSOLE):
            name = adb('emu', 'avd', 'name').stdout.split('\n')[0].strip()
            if name != AVD:
                raise StepFailed(f'console port belongs to {name!r}')
            adb('emu', 'kill')
            for _ in range(30):
                if not emu_lib.port_open(CONSOLE):
                    break
                time.sleep(2)
        LOGS.mkdir(parents=True, exist_ok=True)
        self.log = (LOGS / 'formfactor-emulator.log').open('ab')
        self.process = subprocess.Popen(
            ['emulator', '-avd', AVD, '-port', str(CONSOLE), '-grpc', str(GRPC), '-no-snapshot', '-no-boot-anim', '-no-audio', '-gpu', 'host', '-memory', '4096'],
            cwd=ROOT, env=emu_lib.ENV, stdout=self.log, stderr=subprocess.STDOUT)
        if not wait_boot():
            raise StepFailed('windowed emulator did not boot')
        return self

    def __exit__(self, *_):
        adb('emu', 'kill')
        try:
            self.process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.process.terminate()


class Bridge:
    def __enter__(self):
        go_mod = next((SDK_ENV / 'bridge').rglob('go.mod'))
        env = {**emu_lib.ENV, 'GOROOT': str(SDK_ENV / 'go'), 'GOPATH': str(STATE / 'gopath'), 'GOCACHE': str(STATE / 'gocache'),
               'GOMODCACHE': str(SDK_ENV / 'gopath/pkg/mod'), 'GOTOOLCHAIN': 'local', 'GOFLAGS': '-mod=mod', 'PATH': f"{SDK_ENV / 'go/bin'}:{emu_lib.ENV['PATH']}"}
        env.pop('JAVA_TOOL_OPTIONS', None)
        self.log_path = LOGS / 'formfactor-bridge.log'
        self.log = self.log_path.open('ab')
        self.process = subprocess.Popen(['go', 'run', '.', '--avd', AVD], cwd=go_mod.parent, env=env, stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(45)
        return self

    def alive(self):
        return self.process.poll() is None

    def __exit__(self, *_):
        try:
            os.killpg(self.process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass


def bridge_status():
    out = subprocess.run(['curl', '-sS', '-m', '5', BRIDGE_URL], capture_output=True, text=True)
    return out.stdout


def console_ui():
    adb('shell', 'am', 'start', '--display', '0', '-n', 'ai.pleos.connectwebconsole/.FormFactorConsoleActivity')
    time.sleep(3)


def locate(ui, prefix):
    for node in ui.nodes_full():
        if node.own_text.startswith(prefix) and node.element.get('clickable') == 'true':
            return node.center
    return None


def locate_waiting(ui, prefix, timeout=40):
    """The console is a web page, so its controls appear some time after the activity starts."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        at = locate(ui, prefix)
        if at is not None:
            return at
        time.sleep(2)
    raise StepFailed(f'console control {prefix!r} not found')


def press(ui, prefix):
    at = locate_waiting(ui, prefix)
    adb('shell', 'input', 'tap', str(at[0]), str(at[1]))


def choose(at, offset):
    adb('shell', 'input', 'tap', str(at[0]), str(at[1]))
    time.sleep(2)
    adb('shell', 'input', 'tap', str(at[0]), str(at[1] + offset))
    time.sleep(3)


def select_preset(ui, preset_offset, orientation_offset, display_size):
    """Rows open below each select. The page's own size line is read back, because a tap can land before the list opens."""
    wanted = 'Portrait' if display_size[0] < display_size[1] else 'Landscape'
    choose(locate_waiting(ui, 'Display preset'), preset_offset)
    for _ in range(4):
        choose(locate_waiting(ui, 'Orientation'), orientation_offset)
        line = next((n.own_text for n in ui.nodes_full() if n.own_text.startswith(('Portrait ·', 'Landscape ·'))), '')
        if line.startswith(wanted) and f'{display_size[0]} ×' in line:
            return line
    raise StepFailed(f'console shows {line!r}, wanted {wanted} {display_size}')


def virtual_display_id():
    out = adb('shell', 'cmd', 'display', 'get-displays').stdout
    found = re.findall(r'Display id (\d+): DisplayInfo\{"Emulator', out)
    return int(found[0]) if found else None


def created():
    return '"created":true' in bridge_status()


def remove_virtual(ui):
    """Console button first, as the probe found it; the bridge's documented remove call only if that did not work."""
    if not created():
        return 'none'
    console_ui()
    adb('shell', 'input', 'tap', '2193', '864')
    time.sleep(5)
    if not created():
        return 'console_button'
    try:
        press(ui, '가상 디스플레이 제거')
        time.sleep(5)
    except StepFailed:
        pass
    if not created():
        return 'console_button_dump'
    subprocess.run(['curl', '-sS', '-m', '10', '-X', 'PUT', BRIDGE_URL + '/remove'], capture_output=True, text=True)
    time.sleep(4)
    adb('shell', 'am', 'force-stop', 'ai.pleos.connectwebconsole')
    time.sleep(2)
    return 'bridge_api'


def task_bounds():
    out = adb('shell', 'dumpsys', 'activity', 'activities').stdout
    return re.findall(r'\* Task\{[^\n]*?(ai\.cargo\.planner\S*|car\.settings\S*)[^\n]*\n[^\n]*\n?[^\n]*?mBounds=(Rect\([^)]*\))', out)[:4]


def overflow(ui, right, bottom):
    """Nodes reaching past the window. uiautomator clips to the screen, so only real layout overflow shows here."""
    return [(n.tag, n.right, n.bottom) for n in ui.nodes() if n.tag and (n.right > right + 1 or n.bottom > bottom + 1)]


def journey(result, ui, label, window_right, window_bottom):
    """The core path on this window: V0, V2 form with number pad, V4 with a real surface, one edit, then a kill-free return."""
    observations = result.setdefault('observations', [])

    def note(name, passed, actual=None):
        observations.append({'check': name, 'pass': bool(passed), 'actual': actual})
        print(('  ok  ' if passed else '  FAIL ') + f'{label} {name} {"" if passed else actual}', flush=True)

    note('V0 표시', ui.wait('v0.screen', 40) is not None)
    note('V0 넘침 없음', not overflow(ui, window_right, window_bottom), overflow(ui, window_right, window_bottom))
    result['captures'] = [ui.capture(f'ff-{label}-v0')]
    ui.tap('v0.library')
    ui.wait('v2.screen')
    ui.tap('v2.new')
    ui.wait('v2.form')
    ui.keypad_enter('v2.field.X', 123)
    note('숫자 패드 입력', '123' in ui.text('v2.field.X'), ui.text('v2.field.X'))
    note('V2 넘침 없음', not overflow(ui, window_right, window_bottom), overflow(ui, window_right, window_bottom))
    result['captures'].append(ui.capture(f'ff-{label}-v2'))
    ui.tap('v2.cancel')
    flows.go_home(ui)
    flows.open_scene(ui)
    time.sleep(2)
    state = ui.dump_state()
    surface = state['render']['surface']
    result['surface'] = surface
    note('GL 표면 존재', surface[0] > 0 and surface[1] > 0, surface)
    note('GL 프레임 그림', state['render']['framesDrawn'] > 0, state['render']['framesDrawn'])
    note('V4 넘침 없음', not overflow(ui, window_right, window_bottom), overflow(ui, window_right, window_bottom))
    ui.tap('v4.instance.0')
    ui.tap('v4.step.10')
    before = ui.dump_state()['draft']['instances'][0]['x']
    ui.tap('v4.move.VEHICLE_LEFT')
    time.sleep(1)
    after = ui.dump_state()['draft']['instances'][0]['x']
    note('V4 편집 동작', after - before == 10, (before, after))
    note('긴 문구 표시', ui.text('v4.lastMove') != '', ui.text('v4.lastMove'))
    result['captures'].append(ui.capture(f'ff-{label}-v4'))
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    note('V5 넘침 없음', not overflow(ui, window_right, window_bottom), overflow(ui, window_right, window_bottom))
    result['captures'].append(ui.capture(f'ff-{label}-v5'))
    ui.tap('app.back')
    ui.wait('v4.screen')


def audit_captures(result):
    """Each screen must have its own real file on disk. Identical bytes would mean a screen was not really captured."""
    rows = []
    for relative in result.get('captures', []):
        path = EVIDENCE / relative
        data = path.read_bytes() if path.exists() else b''
        size = list(struct.unpack('>II', data[16:24])) if len(data) >= 24 else None
        rows.append({'path': relative, 'exists': bool(data), 'sha256': hashlib.sha256(data).hexdigest(), 'pixels': size})
    result['capture_audit'] = rows
    distinct = len({r['sha256'] for r in rows})
    result['observations'].append({'check': 'V0·V2·V4·V5 실파일 4개, 서로 다른 화면', 'pass': len(rows) == 4 and all(r['exists'] for r in rows) and distinct == 4, 'actual': distinct})


def status_of(result):
    return 'pass' if result.get('observations') and all(o['pass'] for o in result['observations']) else 'fail'


def main_one(results, ui):
    """Physical main display, one window."""
    flows.fresh_start(ui)
    ui.seed('BASIC')
    flows.restart(ui)
    one = {'grade': 'emulator:main_display_real_window', 'expected_app_area': '1682x1114 (system bars excluded per SDK probe)'}
    try:
        journey(one, ui, 'main-1', 2560, 1440)
        one['task_bounds'] = task_bounds()
        one['status'] = status_of(one)
    except Exception as error:
        one.update(status='fail', error=str(error))
    results['main_one_window'] = one


def main_two(results, ui):
    """Physical main display, two windows through the real window bar."""
    ui.display = None
    flows.fresh_start(ui)
    ui.seed('BASIC')
    flows.restart(ui)
    flows.open_scene(ui)
    two = {'grade': 'emulator:main_display_real_window_bar', 'method': 'window bar handle drag (the probe found it at x=2545 y=720)'}
    try:
        draft_before = ui.dump_state()['draft']
        adb('shell', 'input', 'swipe', '2545', '720', '1700', '720', '1200')
        time.sleep(5)
        two['task_bounds_after_drag'] = task_bounds()
        state = ui.dump_state()
        two['surface_after_drag'] = state['render']['surface']
        two['state_kept_across_resize'] = state['draft']['instances'] == draft_before['instances'] and state['draft']['camera'] == draft_before['camera']
        two['observations'] = [{'check': '창 크기 변경 중 초안·카메라 유지', 'pass': two['state_kept_across_resize'], 'actual': None}]
        adb('shell', 'am', 'start', '-n', SETTINGS)
        time.sleep(5)
        two['task_bounds_two_apps'] = task_bounds()
        two['captures'] = [ui.capture('ff-main-2-side-by-side')]
        nodes = ui.nodes()
        right = max((n.right for n in nodes if n.tag), default=0)
        left = min((n.left for n in nodes if n.tag), default=0)
        two['app_x_range'] = [left, right]
        two['observations'].append({'check': '앱 노드 보임(두 앱 동시 표시)', 'pass': bool([n for n in nodes if n.tag]), 'actual': [left, right]})
        two['observations'].append({'check': '앱 폭이 절반 영역(약 841)', 'pass': 780 <= right - left <= 900, 'actual': right - left})
        if [n for n in nodes if n.tag]:
            ui.tap('v4.detail')
            ui.wait('v5.screen')
            two['observations'].append({'check': '2창에서 V5 조작', 'pass': True, 'actual': None})
            two['captures'].append(ui.capture('ff-main-2-v5'))
            ui.tap('app.back')
            ui.wait('v4.screen')
        two['status'] = status_of(two)
    except Exception as error:
        two.update(status='fail', error=str(error))
    adb('shell', 'am', 'force-stop', 'com.android.car.settings')
    results['main_two_windows'] = two


def one_preset(ui, name, offsets):
    preset_offset, orientation_offset, width, height, display_size = offsets
    result = {'grade': 'emulator:official_console_virtual_display', 'expected_surface_window': [width, height], 'expected_display': display_size, 'physical_form_factor': 'not_claimed'}
    ui.display = None
    result['removed_previous'] = remove_virtual(ui)
    console_ui()
    result['console_line'] = select_preset(ui, preset_offset, orientation_offset, display_size)
    result['console_capture'] = ui.capture(f'ff-{name}-console')
    press(ui, '가상 디스플레이 생성')
    time.sleep(8)
    result['bridge'] = bridge_status()[:200]
    if f'"width":{display_size[0]},' not in result['bridge']:
        raise StepFailed(f'console made another display than {display_size}: {result["bridge"]}')
    display = virtual_display_id()
    if display is None:
        raise StepFailed('virtual display not created')
    result['display_id'] = display
    ui.display = display
    ui.capture_slot = json.loads(result['bridge'])['display_slot']
    ui.capture_size = display_size
    flows.fresh_start(ui)
    ui.force_stop()
    ui.seed('BASIC')
    ui.start()
    time.sleep(4)
    journey(result, ui, name, display_size[0], display_size[1])
    audit_captures(result)
    result['status'] = status_of(result)
    # Second window cannot be made here: this image gives no window bar on virtual displays. Half-width rendering only.
    half = {'grade': 'half_width_render_only_not_two_window', 'method': 'am task resize (not the official window bar)'}
    try:
        tasks = re.findall(r'\* Task\{[0-9a-f]+ #(\d+)[^\n]*ai\.cargo\.planner', adb('shell', 'dumpsys', 'activity', 'activities').stdout)
        half['resize'] = adb('shell', 'am', 'task', 'resize', tasks[0], str(width // 2), '0', str(width), str(height)).stdout[:120]
        time.sleep(4)
        state = ui.dump_state()
        half['surface'] = state['render']['surface']
        half['frames'] = state['render']['framesDrawn']
        half['capture'] = ui.capture(f'ff-{name}-half-width')
        half['nodes_seen'] = len([n for n in ui.nodes() if n.tag])
        half['status'] = 'rendered' if half['nodes_seen'] else 'not_rendered'
    except Exception as error:
        half.update(status='not_run', error=str(error))
    result['half_width'] = half
    result['two_windows'] = {'status': 'not_verified', 'reason': '가상 디스플레이에 창 막대 없음(관측)', 'resume': '창 막대를 제공하는 실차·Devbox·공식 환경에서 이 스크립트의 main_two_windows 절차를 재실행'}
    ui.force_stop()
    ui.display = None
    ui.capture_size = None
    result['removed'] = remove_virtual(ui)
    return result


def presets(results, ui):
    with Bridge() as bridge:
        results['bridge_alive_after_start'] = bridge.alive()
        results['bridge_status'] = bridge_status()[:200]
        if not bridge.alive() or 'ok' not in results['bridge_status']:
            log_tail = bridge.log_path.read_text(errors='replace')[-600:]
            for name in PRESETS:
                results['presets'][name] = {'status': 'blocked', 'reason': 'Host Display Bridge 연결 실패. 다른 AVD 공존 시 탐색 실패가 SDK 점검에서 관측됨', 'log_tail': log_tail, 'resume': '다른 에뮬레이터가 없는 때 python3 apps/vehicle-android/app/tools/run-formfactor.py 재실행. 다른 실행의 에뮬레이터는 임의로 종료하지 않는다'}
            return
        for name, offsets in PRESETS.items():
            print(f'== preset {name}', flush=True)
            try:
                results['presets'][name] = one_preset(ui, name, offsets)
            except Exception as error:
                results['presets'][name] = {'status': 'fail', 'error': f'{type(error).__name__}: {error}'}
                ui.display = None
                ui.capture_size = None
                try:
                    ui.capture(f'ff-{name}-error')
                    # A failed preset must not leave its display for the next one to trip over.
                    results['presets'][name]['removed'] = remove_virtual(ui)
                except Exception:
                    pass


def main():
    results = {'schema': 'vehicle-ui formfactor v1', 'started': now_iso(), 'serial': SERIAL, 'presets': {}, 'real_vehicle': 'not_tested'}
    with WindowedEmulator():
        ui = Ui(PACKAGE)
        install = adb('install', '-r', str(APK), timeout=300)
        results['install'] = install.stdout.strip()[-40:]
        results['apk_sha256'] = emu_lib.sha256(APK)
        # Presets first: the window bar drag leaves the main display split for whatever opens next.
        main_one(results, ui)
        presets(results, ui)
        main_two(results, ui)
    results['finished'] = now_iso()
    groups = [results['main_one_window'], results['main_two_windows'], *results['presets'].values()]
    results['status'] = 'pass' if all(g.get('status') == 'pass' for g in groups) else 'incomplete'
    (EVIDENCE / 'formfactor.json').write_text(json.dumps(results, ensure_ascii=False, indent=1))
    print(json.dumps({'status': results['status'], 'groups': {'main1': results['main_one_window'].get('status'), 'main2': results['main_two_windows'].get('status'), **{k: v.get('status') for k, v in results['presets'].items()}}}, ensure_ascii=False))
    return 0 if results['status'] == 'pass' else 1


if __name__ == '__main__':
    sys.exit(main())
