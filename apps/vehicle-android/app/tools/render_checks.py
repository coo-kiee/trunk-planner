"""Real renderer numbers: framebuffer pixels, CPU picking on real taps, frame counters and process memory."""
import math, re, time
import flows
from ce_checks import seeded
from ui_driver import adb


def rgb(hex_text):
    return tuple(int(hex_text[i:i + 2], 16) for i in (1, 3, 5))


def near_color(actual, expected, tolerance=3):
    return all(abs(a - b) <= tolerance for a, b in zip(rgb(actual), rgb(expected)))


def scaled(hex_text, factor):
    return '#%02X%02X%02X' % tuple(min(255, round(c * factor)) for c in rgb(hex_text))


def pss_kb(ui):
    out = adb('shell', 'dumpsys', 'meminfo', ui.package).stdout
    match = re.search(r'TOTAL PSS:\s*(\d+)', out) or re.search(r'TOTAL\s+(\d+)', out)
    return int(match.group(1)) if match else None


def depth_and_picking(case, ui):
    case.input(seed=seeded(ui, 'OCCLUSION'))
    flows.open_scene(ui)
    ui.tap('v4.cam.opening')
    ui.tap('v4.cam.pitchMinus')
    ui.tap('v4.cam.pitchMinus')
    time.sleep(1)
    state = ui.dump_state()
    render = state['render']
    case.equal('GLES3 컨텍스트', 'OpenGL ES 3' in (render['glVersion'] or ''), True)
    case.check('깊이 버퍼 24비트 이상', render['depthBits'] >= 24, '>=24', render['depthBits'])
    near_row = next(i for i in state['draft']['instances'] if i['z'] == 200)
    far_row = next(i for i in state['draft']['instances'] if i['z'] == 900)
    screen = {i['id']: i for i in render['instances']}
    near, far = screen[near_row['id']], screen[far_row['id']]
    gl = ui.scroll_to('v4.gl')
    # Select the far box: if depth were ignored its selected color would paint over the near box.
    ui.tap_xy(gl.left + int(far['screenX']), gl.top + int(far['screenY']) - 8)
    time.sleep(1)
    picked = ui.dump_state()['render']['lastPick']
    case.input(tap_far=picked)
    ui.tap('v4.instance.1' if ui.dump_state()['draft']['instances'][1]['id'] == far_row['id'] else 'v4.instance.0')
    time.sleep(1)
    render = ui.dump_state()['render']
    roles = {i['id']: i['role'] for i in render['instances']}
    case.equal('뒤쪽 상자 선택 상태', roles[far_row['id']], 'SELECTED')
    near = next(i for i in render['instances'] if i['id'] == near_row['id'])
    x, y = int(near['screenX']), int(near['screenY']) + 40
    ui.dump_state('probe', f'{x},{y}')
    time.sleep(1)
    render = ui.dump_state()['render']
    pixel = render['probes'][0]['rgba']
    palette = render['palette']
    case.input(depth_pixel=pixel, near_expected=scaled(palette['item'], 0.82), far_selected=scaled(palette['selected'], 0.82))
    case.check('앞 상자가 뒤 선택색을 가림(깊이 실측)', near_color(pixel, scaled(palette['item'], 0.82)), scaled(palette['item'], 0.82), pixel)
    case.check('뒤 선택색이 비치지 않음', not near_color(pixel, scaled(palette['selected'], 0.82)), 'not selected', pixel)
    case.record['captures'].append(ui.capture('render-occlusion'))
    # Picking: a real tap at the near box's pixel returns the near box, even with the far box on the same ray.
    gl = ui.scroll_to('v4.gl')
    ui.tap_xy(gl.left + x, gl.top + y)
    time.sleep(1)
    case.equal('같은 시선의 앞 상자 선택', ui.dump_state()['render']['lastPick'], near_row['id'])
    ui.tap_xy(gl.left + 12, gl.top + 12)
    time.sleep(1)
    case.equal('빈 곳 탭은 선택 해제', ui.dump_state()['render']['lastPick'], None)
    return render


def idle_frames(case, ui):
    time.sleep(3)
    first = ui.dump_state()['render']['framesDrawn']
    time.sleep(8)
    delta = ui.dump_state()['render']['framesDrawn'] - first
    case.equal('입력 없는 8초 프레임 증가 0', delta, 0)
    return delta


def scene_cost(case, ui, count):
    case.input(**{f'seed{count}': seeded(ui, f'PERF{count}')})
    flows.open_scene(ui)
    time.sleep(2)
    start = ui.dump_state()['render']
    # Each press is one committed camera change and exactly one requested redraw.
    # The list of 100 items is long, so the buttons are found once and then pressed in place.
    plus = ui.scroll_to('v4.cam.yawPlus', attempts=40).center
    minus = ui.scroll_to('v4.cam.yawMinus', attempts=4).center
    for x, y in [plus, minus] * 8:
        ui.tap_xy(x, y, settle=0.25)
    time.sleep(1)
    end = ui.dump_state()['render']
    drawn = end['framesDrawn'] - start['framesDrawn']
    case.check(f'{count}개 카메라 프레임 실제 증가', drawn > 0, '>0', drawn)
    case.check(f'{count}개 표시 수', len(end['instances']) == count, count, len(end['instances']))
    pss = pss_kb(ui)
    idle = idle_frames(case, ui)
    case.record['captures'].append(ui.capture(f'render-perf-{count}'))
    return {'instances': count, 'framesDuringCameraSteps': drawn, 'timing': end['timing'], 'totalPssKb': pss, 'idleFrameDelta': idle, 'surface': end['surface']}


def measure(case, ui):
    depth_and_picking(case, ui)
    idle_frames(case, ui)
    results = [scene_cost(case, ui, 20), scene_cost(case, ui, 100)]
    case.input(measurements=results)
    for row in results:
        print('  perf', row, flush=True)
        # Own smoothness budget, not a platform requirement. One on-screen frame per request, so the average matters;
        # the worst sample is the first frame, which also builds the mesh, and has a looser bound.
        avg, worst = row['timing'].get('avgMs'), row['timing'].get('maxMs')
        case.check(f"{row['instances']}개 평균 프레임 < 16.7ms", avg is not None and avg < 16.7, '<16.7ms', avg)
        case.check(f"{row['instances']}개 최악 프레임 < 150ms", worst is not None and worst < 150, '<150ms', worst)
        case.check(f"{row['instances']}개 메모리 관측", row['totalPssKb'] is not None, 'kB', row['totalPssKb'])
    case.input(canvas_alternative='GLES3 측정이 통과하여 Canvas 대안을 만들지 않음')


# --- F-02: the default view on the real surface, whichever window size came first ---
TWO_WINDOW_PEER = 'com.android.car.settings/.Settings_Launcher_Homepage'
FIT_SPACE = ([0, 0, 0], [1500, 900, 1500])  # DemoSeeds BASIC
FIT_ITEM = ([0, 0, 0], [300, 200, 300])  # the item the BASIC seed places at the origin
FIT_NARROW = lambda s: 300 <= s[0] < 600 and s[1] >= 900  # noqa: E731
FIT_WIDE = lambda s: s[0] >= 700 and s[1] >= 900  # noqa: E731


def _project(cam, surface, p):
    """ViewMath.project in Python, held to the app's own pixels by the check in _fit."""
    yaw, pitch = math.radians(cam['yaw']), math.radians(cam['pitch'])
    offset = (math.sin(yaw) * math.cos(pitch), math.sin(pitch), -math.cos(yaw) * math.cos(pitch))
    eye = [cam['target'][i] + offset[i] * cam['distance'] for i in range(3)]
    forward = [-v for v in offset]
    right = (-forward[2], 0.0, forward[0])
    norm = math.hypot(*right)
    right = [v / norm for v in right]
    up = (right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0])
    rel = [p[i] - eye[i] for i in range(3)]
    depth = sum(rel[i] * forward[i] for i in range(3))
    half = math.tan(math.radians(45 / 2))
    width, height = surface
    x = (sum(rel[i] * right[i] for i in range(3)) / (depth * half * width / height) + 1) / 2 * width
    y = (1 - sum(rel[i] * up[i] for i in range(3)) / (depth * half)) / 2 * height
    return x, y


def _outside(render, box):
    corners = [(a, b, c) for a in (box[0][0], box[1][0]) for b in (box[0][1], box[1][1]) for c in (box[0][2], box[1][2])]
    width, height = render['surface']
    points = [_project(render['camera'], render['surface'], corner) for corner in corners]
    return [[round(x), round(y)] for x, y in points if not (0 <= x <= width and 0 <= y <= height)]


def _fit(case, ui, label, surface_ok, item=FIT_ITEM):
    state = ui.dump_state()
    render = state['render']
    case.check(f'{label}: 표면 크기', surface_ok(render['surface']), None, render['surface'])
    row = render['instances'][0]
    top = ((item[0][0] + item[1][0]) / 2, item[1][1], (item[0][2] + item[1][2]) / 2)
    mine = _project(render['camera'], render['surface'], top)
    case.check(f'{label}: 시험 투영식 = 앱 ViewMath', abs(mine[0] - row['screenX']) < 3 and abs(mine[1] - row['screenY']) < 3, [row['screenX'], row['screenY']], mine)
    case.equal(f'{label}: 적재 공간 모서리 8개 화면 안', _outside(render, FIT_SPACE), [])
    case.equal(f'{label}: 짐 모서리 8개 화면 안', _outside(render, item), [])
    case.input(**{label: {'surface': render['surface'], 'drawn': render['camera'], 'stored': state['draft']['camera']}})
    return state


def _task_bounds():
    out = adb('shell', 'dumpsys', 'activity', 'activities').stdout
    found = re.findall(r'\* Task\{[^\n]*?ai\.cargo\.planner[^\n]*\n[^\n]*\n?[^\n]*?mBounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)', out)
    return [int(v) for v in found[0]] if found else None


def _narrow(ui):
    """Real two windows on the main display: the window bar is dragged, then a second app takes the other half."""
    adb('shell', 'input', 'swipe', '2545', '720', '1700', '720', '1200')
    time.sleep(5)
    adb('shell', 'am', 'start', '-n', TWO_WINDOW_PEER)
    time.sleep(5)


def _wide(ui):
    adb('shell', 'am', 'force-stop', 'com.android.car.settings')
    time.sleep(3)
    bounds = _task_bounds()
    if bounds and bounds[0] > 900:
        adb('shell', 'input', 'swipe', str(bounds[0] - 12), '720', '860', '720', '1200')
    elif bounds and bounds[2] < 2500:
        adb('shell', 'input', 'swipe', str(bounds[2] + 12), '720', '2545', '720', '1200')
    time.sleep(5)


def _meaning(state):
    draft = state['draft']
    return {'instances': draft['instances'], 'undo': draft['undo'], 'redo': draft['redo'], 'baseRevision': draft['baseRevision']}


def _verdict(ui):
    codes = {}
    for axis in ('placement', 'opening', 'path', 'handling', 'search'):
        codes[axis] = ui.text(f'v4.axis.{axis}.code')
    return codes


def _fresh_scene(ui, open_now=True):
    adb('shell', 'am', 'force-stop', 'com.android.car.settings')
    flows.fresh_start(ui)
    ui.seed('BASIC')
    flows.restart(ui)
    if open_now:
        flows.open_scene(ui)
        time.sleep(2)


def fit_view(case, ui):
    """F-02. A drawn frame proves nothing here: each check projects the corners with the camera actually drawn on the surface."""
    try:
        # A: created in one window, narrowed, widened again.
        _fresh_scene(ui)
        before = _fit(case, ui, 'A 1창 생성', FIT_WIDE)
        stored = before['draft']['camera']
        verdict = _verdict(ui)
        case.input(verdict_before=verdict)
        _narrow(ui)
        narrow = _fit(case, ui, 'A 2창 전환', FIT_NARROW)
        case.record['captures'].append(ui.capture('fit-A-2-window'))
        case.check('A 2창 전환: 그리는 거리만 늘어남', narrow['render']['camera']['distance'] > before['render']['camera']['distance'], f"> {before['render']['camera']['distance']}", narrow['render']['camera']['distance'])
        case.equal('A 2창 전환: 저장 카메라 불변', narrow['draft']['camera'], stored)
        case.equal('A 2창 전환: 물품 좌표·고정·undo 불변', _meaning(narrow), _meaning(before))
        case.equal('A 2창 전환: 판정 표시 불변', _verdict(ui), verdict)
        row = narrow['render']['instances'][0]
        gl = ui.scroll_to('v4.gl')
        ui.tap_xy(gl.left + int(row['screenX']), gl.top + int(row['screenY']))
        time.sleep(1)
        case.equal('A 2창: 탭 picking이 놓인 짐 선택', ui.dump_state()['render']['instances'][0]['role'], 'SELECTED')
        _wide(ui)
        back = _fit(case, ui, 'A 1창 복귀', FIT_WIDE)
        case.equal('A 1창 복귀: 저장 카메라 불변', back['draft']['camera'], stored)
        case.equal('A 1창 복귀: 그리는 거리도 원래 값', back['render']['camera']['distance'], before['render']['camera']['distance'])
        case.equal('A 1창 복귀: 물품 좌표·고정·undo 불변', _meaning(back), _meaning(before))

        # B: created in two windows, then one.
        _fresh_scene(ui, open_now=False)
        ui.wait('v0.screen')
        _narrow(ui)
        # Opened here and not through a restart, which would undo the two windows this case is about.
        ui.tap('v0.scene.0.open')
        ui.wait('v4.screen')
        time.sleep(2)
        made = _fit(case, ui, 'B 2창 생성', FIT_NARROW)
        case.record['captures'].append(ui.capture('fit-B-2-window-created'))
        _wide(ui)
        widened = _fit(case, ui, 'B 1창 전환', FIT_WIDE)
        case.equal('B 1창 전환: 물품 좌표·고정·undo 불변', _meaning(widened), _meaning(made))

        # C: saved and killed, restored in a narrow window.
        _fresh_scene(ui)
        ui.tap('v4.instance.0')
        ui.tap('v4.move.VEHICLE_LEFT')
        time.sleep(2)
        moved = ui.dump_state()
        case.check('C 이동 반영', moved['draft']['instances'][0]['x'] > 0, '> 0', moved['draft']['instances'][0]['x'])
        _narrow(ui)
        pid = ui.alive()
        ui.app_shell(f'kill -9 {pid}')
        time.sleep(3)
        case.check('C 프로세스 실제 종료', ui.alive() != pid, f'not {pid}', ui.alive())
        ui.start()
        time.sleep(3)
        # A killed process starts at home with its draft offered. The second app kept focus, so a touch on empty app space returns it.
        bounds = _task_bounds()
        ui.tap_xy((bounds[0] + bounds[2]) // 2, bounds[3] - 40)
        time.sleep(2)
        ui.wait('v0.screen')
        ui.tap('v0.scene.0.open')
        ui.wait('v4.screen', 40)
        time.sleep(2)
        restored = _fit(case, ui, 'C 복원 후 2창', FIT_NARROW, ([moved['draft']['instances'][0]['x'], 0, 0], [moved['draft']['instances'][0]['x'] + 300, 200, 300]))
        case.record['captures'].append(ui.capture('fit-C-2-window-restored'))
        case.equal('C 복원: 좌표·고정 유지', restored['draft']['instances'], moved['draft']['instances'])
        _wide(ui)

        # D: a camera the user chose survives every window change, in the draft and on the screen.
        _fresh_scene(ui)
        ui.tap('v4.cam.yawPlus')
        ui.tap('v4.cam.farther')
        time.sleep(1)
        chosen = ui.dump_state()
        case.check('D 사용자 카메라 지정됨', chosen['draft']['camera'] != stored, f'not {stored}', chosen['draft']['camera'])
        _narrow(ui)
        side = ui.dump_state()
        case.equal('D 2창: 저장 카메라 유지', side['draft']['camera'], chosen['draft']['camera'])
        case.equal('D 2창: 그리는 카메라도 그대로', side['render']['camera'], chosen['render']['camera'])
        case.equal('D 2창: undo 불변', _meaning(side), _meaning(chosen))

        # E: "전체 보기" returns to the default, and it fits this narrow surface.
        ui.tap('v4.cam.fit')
        time.sleep(1)
        fitted = _fit(case, ui, 'E 전체 보기(2창)', FIT_NARROW)
        case.equal('E 전체 보기: 저장값은 기본 시점', fitted['draft']['camera'], stored)
        case.check('E 기본 시점에서는 버튼 숨김', not ui.has('v4.cam.fit'), 'absent', ui.has('v4.cam.fit'))

        # F: from the fitted view "멀리" moves away from what is on screen, and "가까이" is the user's own choice to clip.
        drawn = fitted['render']['camera']['distance']
        ui.tap('v4.cam.farther')
        time.sleep(1)
        farther = ui.dump_state()
        case.check('F 좁은 창에서 멀리는 실제로 멀어짐', farther['render']['camera']['distance'] > drawn, f'> {drawn}', farther['render']['camera']['distance'])
        ui.tap('v4.cam.fit')
        time.sleep(1)
        ui.tap('v4.cam.closer')
        time.sleep(1)
        closer = ui.dump_state()
        case.check('F 좁은 창에서 가까이는 실제로 가까워짐', closer['render']['camera']['distance'] < drawn, f'< {drawn}', closer['render']['camera']['distance'])
        _wide(ui)
        again = ui.dump_state()
        case.equal('F 1창 복귀: 사용자 카메라 그대로', again['render']['camera'], closer['render']['camera'])
    finally:
        adb('shell', 'am', 'force-stop', 'com.android.car.settings')
        adb('shell', 'am', 'force-stop', ui.package)


CASES_F02 = {'fit-view': ('2창에서도 기본 3D 시점이 공간과 짐 전체를 보임', 'emulator:seeded_real_store+real_gl+real_window_bar', fit_view)}
