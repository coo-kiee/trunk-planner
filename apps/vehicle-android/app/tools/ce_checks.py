"""Counterexample cases on real screens. Stored states come from real_ui_input or seeded_real_store, never injected UI state."""
import time
import flows
from core_flow import wait_axes, drive, park, wait_ux, pose_of
from ui_driver import StepFailed, adb, emu_lib

GRADE_SEEDED = 'emulator:seeded_real_store'
GRADE_UI = 'emulator:real_ui_input'
GRADE_STATE = 'emulator:fixture_state_on_real_screen'


def seeded(ui, *names):
    flows.fresh_start(ui)
    results = [ui.seed(name) for name in names]
    flows.restart(ui)
    ui.wait('v0.screen')
    return results


def code(ui, screen, axis):
    return ui.text(f'{screen}.axis.{axis}.code')


def detail(ui):
    ui.tap('v4.detail')
    ui.wait('v5.screen')


def ce03(case, ui):
    case.input(seed=seeded(ui, 'CE03A'))
    flows.open_scene(ui)
    detail(ui)
    case.equal('A 내부 CONFLICT', code(ui, 'v5', 'placement'), 'CONFLICT')
    case.equal('A 입구 FITS', code(ui, 'v5', 'opening'), 'FITS')
    case.check('A 들어감 배지 없음', not ui.has('v5.fits'), 'absent', ui.has('v5.fits'))
    case.record['captures'].append(ui.capture('CE-03-02-a'))
    case.input(seed_b=seeded(ui, 'CE03B'))
    flows.open_scene(ui)
    detail(ui)
    case.equal('B 내부 CLEAR', code(ui, 'v5', 'placement'), 'CLEAR')
    case.equal('B 입구 DOES_NOT_FIT', code(ui, 'v5', 'opening'), 'DOES_NOT_FIT')
    case.check('B 전체 통과 배지 없음', not ui.has('v5.fits'), 'absent', ui.has('v5.fits'))
    case.contains('B 입구 문구', ui.text('v5.axis.opening'), '입구에 들지 않음')
    case.record['captures'].append(ui.capture('CE-03-02-b'))


def ce04(case, ui):
    case.input(seed=seeded(ui, 'CE04'))
    flows.open_scene(ui)
    detail(ui)
    case.equal('OPENING_UNMEASURED 표시', code(ui, 'v5', 'opening'), 'OPENING_UNMEASURED')
    text = ui.text('v5.axis.opening')
    case.contains('측정 안내', text, '재서 공간 설정에 입력')
    for word in ('✓', '통과', '들어감'):
        case.lacks(f'통과 표현 없음({word})', text, word)
    case.check('들어감 배지 없음', not ui.has('v5.fits'), 'absent', ui.has('v5.fits'))
    case.record['captures'].append(ui.capture('CE-04-02'))


def ce05(case, ui):
    case.input(seed=seeded(ui, 'CE05'))
    flows.open_scene(ui)
    detail(ui)
    ui.tap('v5.validate')
    time.sleep(2)
    case.equal('입구 FITS', code(ui, 'v5', 'opening'), 'FITS')
    case.equal('경로 UNVERIFIED 독립 표시', code(ui, 'v5', 'path'), 'UNVERIFIED')
    case.contains('입구 문구가 경로와 별개라고 명시', ui.text('v5.axis.opening'), '경로 통과와 별개')
    case.contains('경로 미검증 문구', ui.text('v5.axis.path'), '미검증')
    case.record['captures'].append(ui.capture('CE-05-02'))


def ce06(case, ui):
    # A process that died mid-search leaves this exact marker in the draft.
    case.input(seed=seeded(ui, 'CE06DEATH'))
    flows.open_scene(ui)
    case.contains('앱 종료 중단 안내', ui.text('v4.interrupted'), '불가 판정이 아닙니다')
    case.equal('V4 SEARCH_INTERRUPTED', code(ui, 'v4', 'search'), 'SEARCH_INTERRUPTED')
    text = ui.text('v4.axis.search')
    case.contains('탐색 중단 문구', text, '탐색 중단')
    case.contains('이어할 수 있다는 문구', text, '이어할 수')
    case.check('이어하기 동작 제공', ui.scroll_to('v4.searchResume').enabled, 'enabled', None)
    case.record['captures'].append(ui.capture('CE-06-02-death'))
    detail(ui)
    case.equal('V5 SEARCH_INTERRUPTED', code(ui, 'v5', 'search'), 'SEARCH_INTERRUPTED')
    case.lacks('V5 적재불가 확정 문구 없음', ui.text('v5.axis.search'), '놓을 자리가 없음')
    # Budget: the real search with a real limit of zero checks.
    case.input(seed_budget=seeded(ui, 'BASIC'))
    ui.set_flag('search_budget', '0')
    flows.restart(ui)
    flows.open_scene(ui)
    ui.tap('v4.add.0.search')
    time.sleep(2)
    case.equal('예산 초과 SEARCH_INTERRUPTED', code(ui, 'v4', 'search'), 'SEARCH_INTERRUPTED')
    case.contains('예산 사유', ui.text('v4.axis.search'), '검사 한도')
    ui.clear_flag('search_budget')
    ui.tap('v4.searchResume')
    ui.wait_scrolled('v4.offer', 60)
    case.equal('이어하기 후 후보 발견', code(ui, 'v4', 'search'), 'FOUND_PLACEMENT')
    case.record['captures'].append(ui.capture('CE-06-02-resume'))


def search05(case, ui):
    case.input(seed=seeded(ui, 'SEARCH05'))
    flows.open_scene(ui)
    row = next(i for i in range(4) if '빈자리 없는' in ui.text(f'v4.add.{i}'))
    ui.tap(f'v4.add.{row}.search')
    time.sleep(3)
    case.equal('NO_CANDIDATE_UNCERTIFIED', code(ui, 'v4', 'search'), 'NO_CANDIDATE_UNCERTIFIED')
    text = ui.text('v4.axis.search')
    case.contains('후보 미발견 정확 문구', text, '검사한 후보에서 배치를 찾지 못했습니다. 적재 불가로 증명되지는 않았습니다.')
    case.lacks('중단 문구 아님', text, '탐색 중단')
    case.lacks('미실행 문구 아님', text, '실행하지 않음')
    case.lacks('불가 확정 문구 아님', text, '놓을 자리가 없음')
    case.check('후보 제시 없음', not ui.has('v4.offer'), 'absent', ui.has('v4.offer'))
    case.record['captures'].append(ui.capture('S-SEARCH-05'))


def ce09(case, ui):
    case.input(seed=seeded(ui, 'BASIC'))
    flows.open_scene(ui)
    ui.tap('v4.instance.0')
    ui.tap('v4.poseEntry')
    ui.keypad_enter('v4.pose.Y', 300)
    ui.tap('v4.pose.apply')
    axes = wait_axes(ui)
    case.equal('공중 배치 취급 VIOLATION', axes['handling'], 'VIOLATION')
    case.contains('공중 표시', ui.text('v4.issue.0'), '공중')
    case.contains('안전 미판정 유지', ui.text('v4.axis.safety'), '안전·고정·중량: 판정하지 않음')
    ui.tap('v4.confirm')
    case.contains('공중 배치 확정 거부', ui.text('v4.rejection'), '확정할 수 없습니다')
    case.record['captures'].append(ui.capture('CE-09-02-airborne'))
    # Half on top of another box: partial support from real geometry.
    ui.tap('v4.undo')
    time.sleep(1)
    ui.tap('v4.add.0.direct')
    time.sleep(1)
    ui.tap('v4.poseEntry')
    ui.keypad_enter('v4.pose.X', 150)
    ui.keypad_enter('v4.pose.Y', 200)
    ui.tap('v4.pose.apply')
    axes = wait_axes(ui)
    case.equal('지지 부족 취급 VIOLATION', axes['handling'], 'VIOLATION')
    case.contains('지지 부족 표시', ui.text('v4.issue.0'), '지지 부족')
    case.contains('안전 미판정 함께 유지', ui.text('v4.axis.safety'), '판정하지 않음')
    detail(ui)
    case.contains('V5 문제 목록', ui.text('v5.issue.0'), '지지 부족')
    case.contains('V5 안전 미판정', ui.text('v5.axis.safety'), '판정하지 않음')
    case.record['captures'].append(ui.capture('CE-09-02-support'))


def ce10(case, ui):
    case.input(seed=seeded(ui, 'BASIC'))
    flows.open_scene(ui)
    before = ui.dump_state()['draft']
    case.equal('시작 판정 CLEAR', code(ui, 'v4', 'placement'), 'CLEAR')
    flows.go_home(ui)
    ui.tap('v0.library')
    ui.wait('v2.screen')
    ui.tap('v2.item.0.newVersion')
    ui.wait('v2.form')
    ui.keypad_enter('v2.field.X', 350)
    ui.tap('v2.confirm')
    ui.tap('v2.save')
    case.contains('새 버전 안내: 기존 공간 유지', ui.text('v2.message'), '이전 버전을 그대로 참조')
    case.contains('보관함 r2', ui.text('v2.item.0.title'), 'r2')
    case.contains('공간은 r1 참조 유지', ui.text('v2.item.0.use'), '물품 r1 참조')
    case.contains('미적용 표기', ui.text('v2.item.0.use'), '새 버전 미적용')
    case.record['captures'].append(ui.capture('CE-10-02-library'))
    flows.open_scene(ui)
    ui.tap('v4.instance.0')
    case.equal('적용 전 판정 유지(stale 아님)', ui.has('v4.stale'), False)
    case.equal('적용 전 물품 revision 불변', ui.dump_state()['draft']['instances'][0]['itemRevision'], before['instances'][0]['itemRevision'])
    case.contains('새 버전 제안 표시', ui.text('v4.newerItem'), '판정은 바뀌지 않았습니다')
    ui.tap('v4.applyItem')
    time.sleep(1)
    case.contains('적용 후 재검증 대기', ui.text('v4.stale'), '물품 새 버전')
    case.equal('적용 후 내부 판정 STALE', code(ui, 'v4', 'placement'), 'STALE')
    case.check('적용 후 들어감 없음', not ui.has('v4.fits'), 'absent', ui.has('v4.fits'))
    case.equal('적용 후 좌표 그대로', ui.dump_state()['draft']['instances'][0]['x'], before['instances'][0]['x'])
    case.record['captures'].append(ui.capture('CE-10-02-applied'))
    ui.tap('v4.validate')
    time.sleep(2)
    case.equal('재검증 뒤 stale 해제', ui.has('v4.stale'), False)


def ce11(case, ui):
    for seed, needle in (('CE11ENGINE', '엔진'), ('CE11CONFIG', '설정')):
        case.input(**{seed: seeded(ui, seed)})
        flows.open_scene(ui)
        case.contains(f'{seed} 사유', ui.text('v4.stale'), needle)
        case.equal(f'{seed} 내부 판정 숨김', code(ui, 'v4', 'placement'), 'STALE')
        case.check(f'{seed} 들어감 없음', not ui.has('v4.fits'), 'absent', ui.has('v4.fits'))
        case.equal(f'{seed} 입구 통과색 없음', code(ui, 'v4', 'opening'), 'STALE')
        detail(ui)
        case.contains(f'{seed} V5 사유', ui.text('v5.stale'), needle)
        case.equal(f'{seed} V5 숨김', code(ui, 'v5', 'path'), 'STALE')
        case.record['captures'].append(ui.capture(f'CE-11-02-{seed}'))
        ui.tap('v5.validate')
        time.sleep(2)
        case.equal(f'{seed} 재검증 뒤 해제', ui.has('v5.stale'), False)
    case.input(CE11PROFILE=seeded(ui, 'CE11PROFILE'))
    flows.open_scene(ui)
    case.contains('공간 새 버전 제안', ui.text('v4.newerProfile'), 'r2')
    case.equal('적용 전 stale 아님', ui.has('v4.stale'), False)
    ui.tap('v4.applyProfile')
    time.sleep(1)
    case.contains('공간 적용 후 사유', ui.text('v4.stale'), '공간 새 버전')
    case.equal('공간 적용 후 숨김', code(ui, 'v4', 'placement'), 'STALE')
    case.record['captures'].append(ui.capture('CE-11-02-profile'))


def _prepare_save(ui):
    flows.open_scene(ui)
    ui.tap('v4.instance.0')
    ui.tap('v4.step.50')
    ui.tap('v4.move.INWARD')
    wait_axes(ui)
    flows.wait_enabled(ui, 'v4.confirm')
    ui.tap('v4.confirm')
    flows.wait_enabled(ui, 'v4.save')


def _parked(ui, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if 'SAVE_PARKED' in adb('logcat', '-d', '-s', 'CargoSeed:I').stdout:
            return True
        time.sleep(0.5)
    return False


def ce12(case, ui):
    for point, expected_head, new_pose in (('REVISION_INSERTED', 'r1', False), ('HEAD_UPDATED', 'r1', False), ('COMMITTED', 'r2', True)):
        case.input(**{f'seed_{point}': seeded(ui, 'BASIC')})
        ui.set_flag('save_fault', point)
        flows.restart(ui)
        adb('logcat', '-c')
        _prepare_save(ui)
        ui.tap('v4.save', settle=0.3)
        case.check(f'{point}: 저장 지점 도달', _parked(ui), True, None)
        nodes = ui.nodes()
        case.check(f'{point}: 완료 표시 없음', ui.find('v4.saved', nodes) is None, 'absent', None)
        case.record['captures'].append(ui.capture(f'CE-12-02-{point}-parked'))
        ui.force_stop()
        case.equal(f'{point}: 외부 강제 종료', ui.alive(), '')
        ui.clear_flag('save_fault')
        ui.start()
        ui.wait('v0.screen')
        title = ui.text('v0.scene.0.title')
        case.contains(f'{point}: 목록은 한 저장본만', title, f'저장본 {expected_head}')
        flows.open_scene(ui)
        state = ui.dump_state()['draft']
        case.equal(f'{point}: 초안 기준 저장본', state['baseRevision'], int(expected_head[1:]))
        case.equal(f'{point}: 좌표', state['instances'][0]['z'], 50)
        case.check(f'{point}: 저장 완료 허위 표시 없음', not ui.has('v4.saved'), 'absent', ui.has('v4.saved'))
        case.record['captures'].append(ui.capture(f'CE-12-02-{point}-after'))


def ce13(case, ui):
    case.input(seed=seeded(ui, 'BASIC'))
    _prepare_save(ui)
    ui.tap('v4.save')
    ui.wait_scrolled('v4.saved', 30)
    case.input(damage=ui.seed('CE13CHECKSUM'))
    flows.restart(ui)
    ui.wait('v0.screen')
    case.contains('목록 손상 표시', ui.text('v0.scene.0.health'), '직전 r1 읽기 전용')
    case.contains('격리 표시', ui.text('v0.quarantine'), '원본은 보존')
    flows.open_scene(ui)
    case.contains('원본 보존·직전 정상 안내', ui.text('v4.readOnly.status'), '원본은 보존')
    nodes = ui.nodes()
    for tag in ('v4.save', 'v4.confirm', 'v4.validate', 'v4.undo', 'v4.add', 'v4.instances', 'v4.selected'):
        case.check(f'읽기 전용: {tag} 없음', ui.find(tag, nodes) is None, 'absent', None)
    state = ui.dump_state()['draft']
    case.equal('r1 내용 표시', (state['baseRevision'], state['instances'][0]['z']), (1, 0))
    case.record['captures'].append(ui.capture('CE-13-02-readonly'))
    damaged = ui.app_shell("sha256sum databases/cargo.db").stdout.split(' ')[0]
    flows.go_home(ui)
    ui.tap('v0.recovery')
    ui.wait('v7.screen')
    case.contains('V7 격리 행', ui.text('v7.row.0'), 'scene_revision')
    case.record['captures'].append(ui.capture('CE-13-02-v7'))
    case.input(db_sha_after_view=damaged)
    # Explicit recovery: a new head with the good content. The damaged row stays as it was.
    flows.go_home(ui)
    flows.open_scene(ui)
    ui.tap('v4.adopt')
    ui.wait_scrolled('v4.instances', 30)
    state = ui.dump_state()['draft']
    case.equal('복구 채택 후 새 저장본 r3', state['baseRevision'], 3)
    case.equal('복구 채택 후 r1 내용', state['instances'][0]['z'], 0)
    case.contains('손상 원본 격리 유지', ui.app_shell("ls databases").stdout, 'cargo.db')
    # Future schema: real file header of a newer app.
    case.input(seed_future=seeded(ui, 'BASIC'))
    case.input(future=ui.seed('CE13FUTURE'))
    flows.restart(ui)
    ui.wait('v7.screen')
    case.contains('미래 형식 안내', ui.text('v7.future'), '쓰지 않습니다')
    case.contains('쓰기 금지 안내', ui.text('v7.writeBlocked'), '비활성화')
    nodes = ui.nodes()
    case.check('편집 진입 없음', ui.find('v0.screen', nodes) is None and ui.find('v4.screen', nodes) is None, 'absent', None)
    version = ui.app_shell('od -A n -t u1 -j 60 -N 4 databases/cargo.db').stdout.split()
    case.equal('미래 형식 파일 헤더 유지(쓰기 없음)', version[-1] if version else None, '99')
    case.record['captures'].append(ui.capture('CE-13-02-future'))


def ce14(case, ui):
    case.input(seed=seeded(ui, 'BASIC'))
    case.input(version=ui.seed('CE14MIGRATION'))
    ui.force_stop()
    before = ui.app_shell('sha256sum databases/cargo.db').stdout.split(' ')[0]
    ui.start()
    ui.wait('v7.screen')
    case.contains('실패 안내', ui.text('v7.migrationFailed'), '덮어쓰지 않았습니다')
    case.contains('원본 불변 확인', ui.text('v7.originalUnchanged'), '확인됨')
    case.contains('사전 사본 일치', ui.text('v7.backupMatches'), '확인됨')
    backup = ui.text('v7.backupDir')
    case.contains('사본 위치 표시', backup, '위치')
    case.contains('재개 방법', ui.text('v7.resume'), '다시 시작')
    nodes = ui.nodes()
    case.check('편집·저장 진입 없음', ui.find('v0.screen', nodes) is None and ui.find('v4.save', nodes) is None, 'absent', None)
    case.record['captures'].append(ui.capture('CE-14-02'))
    ui.force_stop()
    after = ui.app_shell('sha256sum databases/cargo.db').stdout.split(' ')[0]
    case.check('실패 DB 원본 바이트 동일', bool(before) and before == after, before, after)
    listing = ui.app_shell('find . -name "cargo.db" -not -path "./cache/*"').stdout
    case.check('사전 사본 파일 존재', listing.count('cargo.db') >= 2, '>=2', listing)
    ui.start()
    ui.wait('v7.screen')
    case.contains('재실행해도 같은 안전 화면', ui.text('v7.migrationFailed'), '덮어쓰지 않았습니다')


def ce15(case, ui):
    from core_flow import recreate
    case.input(seed=seeded(ui, 'BASIC'))
    flows.open_scene(ui)
    # recreate() works on the second instance; give it one to work on.
    flows.search_and_place(ui, 0)
    wait_axes(ui)
    ui.tap('v4.instance.1')
    recreate(case, ui, 'CE-15-02')
    # Gesture: a real drag on the 3D view moves the camera and commits at its end.
    gl = ui.scroll_to('v4.gl')
    start = ui.dump_state()['draft']['camera']
    x, y = gl.center
    adb('shell', 'input', 'swipe', str(x), str(y), str(x + 200), str(y + 40), '400')
    time.sleep(1)
    moved = ui.dump_state()['draft']['camera']
    case.check('3D 드래그로 카메라 변경·확정', moved != start, 'changed', moved)
    # Home on this image may leave the app on screen in its pane, so the gate is only recorded here. No frames either way.
    adb('shell', 'input', 'keyevent', '3')
    time.sleep(3)
    home = ui.dump_state()
    case.input(gate_after_home=home['render']['gateReason'])
    time.sleep(4)
    case.equal('HOME 뒤 무입력 프레임 증가 0', ui.dump_state()['render']['framesDrawn'] - home['render']['framesDrawn'], 0)
    ui.start()
    # Another app takes the window: the activity stops and drawing stops with it, by the lifecycle rather than a guess.
    adb('shell', 'am', 'start', '-n', 'com.android.car.settings/.Settings_Launcher_Homepage')
    time.sleep(5)
    stopped = ui.dump_state()
    case.equal('다른 앱에 가려지면 게이트 닫힘', stopped['render']['gateOpen'], False)
    case.equal('닫힘 사유는 lifecycle', stopped['render']['gateReason'], 'LIFECYCLE_STOPPED')
    frames = stopped['render']['framesDrawn']
    time.sleep(5)
    case.equal('가려진 동안 프레임 증가 0', ui.dump_state()['render']['framesDrawn'] - frames, 0)
    case.record['captures'].append(ui.capture('CE-15-02-background'))
    # Saved-state path: the system kills the stopped process and the same screen returns.
    adb('shell', 'am', 'kill', '--user', ui.user, ui.package)
    time.sleep(2)
    case.equal('배경 프로세스 종료', ui.alive(), '')
    ui.start()
    ui.wait('v4.screen', 40)
    time.sleep(1)
    back = ui.dump_state()
    case.equal('저장상태 복귀: 같은 화면', back['routes'].split('>')[-1], 'V4')
    case.equal('저장상태 복귀: 같은 카메라', back['draft']['camera'], moved)
    case.equal('복귀 후 게이트 열림', back['render']['gateOpen'], True)
    adb('shell', 'am', 'force-stop', 'com.android.car.settings')


def ce16(case, ui):
    from core_flow import driving_restriction
    case.input(seed=seeded(ui, 'BASIC'))
    flows.open_scene(ui)
    flows.search_and_place(ui, 0)
    wait_axes(ui)
    driving_restriction(case, ui, 'CE-16-02')
    with emu_lib.VehicleConsole() as console:
        flows.go_home(ui)
        ui.tap('v0.pairing')
        ui.wait('v3.screen')
        case.check('정차 중 QR 조작 노출', ui.has('v3.start') or ui.has('v3.disabledNote'), 'present', None)
        case.input(drive_v3=drive(console))
        wait_ux(ui, True)
        time.sleep(1)
        nodes = ui.nodes()
        case.check('주행 중 QR 없음', ui.find('v3.qr', nodes) is None and ui.find('v3.start', nodes) is None, 'absent', None)
        case.check('QR 숨김 안내', ui.find('v3.qrHidden', nodes) is not None or ui.find('v3.disabledNote', nodes) is not None, 'present', None)
        case.record['captures'].append(ui.capture('CE-16-02-qr-hidden'))
        case.input(park_v3=park(console))
        wait_ux(ui, False)
        ui.wait('v3.start')


def v5_driving(case, ui):
    """An interrupted search on V5: its resume button is the one action that would change the draft while driving."""
    case.input(seed=seeded(ui, 'CE06DEATH'))
    flows.open_scene(ui)
    detail(ui)
    ui.wait_scrolled('v5.searchResume')
    before = ui.dump_state()['draft']
    with emu_lib.VehicleConsole() as console:
        case.input(drive=drive(console))
        wait_ux(ui, True)
        time.sleep(2)
        nodes = ui.nodes()
        for tag in ('v5.validate', 'v5.searchResume'):
            case.check(f'주행 중 {tag} 없음', ui.find(tag, nodes) is None, 'absent', None)
        case.check('주행 중 제한 안내', ui.find('v5.restricted', nodes) is not None, 'present', None)
        case.contains('주행 중 탐색 상태 읽기 유지', ui.text('v5.axis.search.code'), 'SEARCH_INTERRUPTED')
        case.record['captures'].append(ui.capture('V5-driving-restricted'))
        # What was tapped in the review: the old button position. Nothing may run from there now.
        ui.tap_xy(ui.find('v5.axes', nodes).left + 60, ui.find('v5.axes', nodes).bottom - 40)
        time.sleep(2)
        case.contains('탭 뒤에도 탐색 상태 불변', ui.text('v5.axis.search.code'), 'SEARCH_INTERRUPTED')
        case.equal('주행 중 초안 불변', ui.dump_state()['draft']['instances'], before['instances'])
        case.input(park=park(console))
        wait_ux(ui, False)
        ui.wait_scrolled('v5.searchResume')
        restored = ui.dump_state()['draft']
        case.equal('정차 후 같은 초안', (restored['instances'], restored['camera'], restored['undo']), (before['instances'], before['camera'], before['undo']))
        case.check('정차 후 재검증 복귀', ui.wait_scrolled('v5.validate') is not None, 'present', None)
        case.record['captures'].append(ui.capture('V5-parked-restored'))
    # The checklist ticks follow the same rule.
    case.input(seed_v6=seeded(ui, 'OCCLUSION'))
    flows.open_scene(ui)
    ui.tap('v4.checklist')
    ui.wait('v6.screen')
    ui.tap('v6.load.0.check')
    with emu_lib.VehicleConsole() as console:
        drive(console)
        wait_ux(ui, True)
        time.sleep(2)
        nodes = ui.nodes()
        case.check('주행 중 V6 체크 동작 없음', ui.find('v6.load.0.check', nodes) is None, 'absent', None)
        case.check('주행 중 V6 순서 읽기 유지', ui.find('v6.load.0.title', nodes) is not None, 'present', None)
        park(console)
        wait_ux(ui, False)
        case.contains('정차 후 체크 유지', ui.wait_scrolled('v6.load.0.check').text, '확인함')


PORT_STATES = {
    'NOT_READY': ('NOT_READY_SERVICE_NOT_CONNECTED', '연결 대기'),
    'UNSUPPORTED_ABSENT': ('UNSUPPORTED_CAPABILITY_ABSENT', '제공하지 않습니다'),
    'UNSUPPORTED_NULL': ('UNSUPPORTED_CAPABILITY_NULL', '값 없음'),
    'UNSUPPORTED_REJECTED': ('UNSUPPORTED_AREA_REJECTED', '거부'),
    'PERMISSION_MISSING': ('PERMISSION_MISSING', '권한 누락'),
    'FAILED': ('FAILED_CONNECTION_TIMEOUT', '연결 실패'),
}


def ce17(case, ui):
    flows.fresh_start(ui)
    case.contains('실제 SDK 문 상태 코드', ui.text('v0.doorCode'), 'AVAILABLE')
    case.input(real_sdk_door=ui.text('v0.doorCode'), real_sdk_grade='emulator:real_sdk')
    seen = {}
    for name, (expected, word) in PORT_STATES.items():
        ui.set_flag('port_state', name)
        flows.restart(ui)
        ui.wait('v0.screen')
        shown = ui.text('v0.doorCode')
        case.contains(f'{name}: 고유 코드', shown, expected)
        case.contains(f'{name}: 문구', ui.text('v0.door'), word)
        seen[name] = shown
        case.record['captures'].append(ui.capture(f'CE-17-02-{name}'))
    case.equal('상태 서로 구분됨', len(set(seen.values())), len(PORT_STATES))
    # A missing UX value fails closed on the real screen, with its own reason.
    ui.set_flag('port_state', 'PERMISSION_MISSING')
    flows.restart(ui)
    ui.wait('v0.screen')
    case.contains('UX 권한 누락 코드', ui.text('app.uxCode'), 'UX_UNAVAILABLE_PERMISSION_MISSING')
    case.check('값 없으면 새 배치 비활성(mock 성공 없음)', not ui.find('v0.profile').enabled, 'disabled', None)
    ui.clear_flag('port_state')


def ce21(case, ui):
    case.input(seed=seeded(ui, 'BASIC'))
    flows.open_scene(ui)
    ui.tap('v4.instance.0')
    ui.tap('v4.cam.opening')
    ui.tap('v4.step.100')
    time.sleep(1)
    case.contains('입구 시점 안내', ui.text('v4.viewHint'), '차량 왼쪽이 화면 왼쪽')
    before = ui.dump_state()
    start_x = before['render']['instances'][0]['screenX']
    ui.tap('v4.move.VEHICLE_LEFT')
    time.sleep(1)
    after = ui.dump_state()
    case.contains('문구: 차량 왼쪽', ui.text('v4.lastMove'), '차량 왼쪽으로 100mm')
    case.equal('좌표: +x 100', after['draft']['instances'][0]['x'] - before['draft']['instances'][0]['x'], 100)
    case.check('입구 시점 화면 x 감소', after['render']['instances'][0]['screenX'] < start_x, f'< {start_x}', after['render']['instances'][0]['screenX'])
    # Real framebuffer at the projected spot, so the claim covers what was drawn and not only the math.
    x, y = int(after['render']['instances'][0]['screenX']), int(after['render']['instances'][0]['screenY'])
    ui.dump_state('probe', f'{x},{y}')
    time.sleep(1)
    pixels = ui.dump_state()['render']
    palette = pixels['palette']['selected']
    case.input(probe=pixels['probes'], selected=palette)
    case.equal('이동한 위치 실제 픽셀이 선택색', pixels['probes'][0]['rgba'], palette)
    case.record['captures'].append(ui.capture('CE-21-02-opening'))
    ui.tap('v4.cam.cabin')
    time.sleep(1)
    case.contains('안쪽 시점 안내', ui.text('v4.viewHint'), '차량 왼쪽이 화면 오른쪽')
    behind = ui.dump_state()['render']['instances'][0]['screenX']
    ui.tap('v4.move.VEHICLE_LEFT')
    time.sleep(1)
    now = ui.dump_state()['render']['instances'][0]['screenX']
    case.contains('문구는 여전히 차량 왼쪽', ui.text('v4.lastMove'), '차량 왼쪽으로 100mm')
    case.check('반대 시점 화면 x 증가', now > behind, f'> {behind}', now)
    case.record['captures'].append(ui.capture('CE-21-02-cabin'))


def ce22(case, ui):
    flows.fresh_start(ui)
    case.contains('V0 근거 등급', ui.text('app.grade'), '실차 미시험')
    case.input(seed=ui.seed('BASIC'))
    flows.restart(ui)
    flows.open_scene(ui)
    case.contains('V4 근거 등급', ui.text('app.grade'), '실차 미시험')
    detail(ui)
    case.contains('V5 근거 등급', ui.text('app.grade'), '에뮬레이터')
    case.record['captures'].append(ui.capture('CE-22-03'))


def profiles(case, ui):
    flows.fresh_start(ui)
    ui.tap('v0.profile')
    ui.wait('v1.screen')
    ui.tap('v1.unsupported')
    case.contains('UNSUPPORTED 판정 없음', ui.text('v1.unsupportedStatus'), '판정을 제공하지 않습니다')
    case.check('직접측정 안내 동작', ui.has('v1.unsupportedMeasure'), 'present', None)
    case.check('UNSUPPORTED 행에 배치 시작 없음', not ui.has('v1.row.0.start'), 'absent', ui.has('v1.row.0.start'))
    case.record['captures'].append(ui.capture('S-PROFILE-01'))
    case.input(unsupported=ui.seed('UNSUPPORTEDSCENE'))
    flows.restart(ui)
    ui.wait('v0.screen')
    flows.open_scene(ui)
    ui.tap('v4.validate')
    time.sleep(2)
    case.contains('core가 판정 거부', ui.text('v4.notAvailable'), 'PROFILE_UNSUPPORTED')
    case.equal('축 코드도 판정 아님', code(ui, 'v4', 'placement'), 'NOT_AVAILABLE_PROFILE_UNSUPPORTED')
    case.record['captures'].append(ui.capture('S-PROFILE-01-editor'))
    flows.fresh_start(ui)
    flows.create_demo_profile(ui)
    case.contains('V1 합성 라벨', ui.text('v1.row.0.grade'), '실제 차량 치수 아님')
    flows.go_home(ui)
    flows.add_item(ui, 'demo-box', 200, 200, 200)
    flows.go_home(ui)
    ui.tap('v0.profile')
    ui.wait('v1.row.0')
    ui.tap('v1.row.0.start')
    ui.wait('v4.screen')
    case.contains('V4 합성 라벨', ui.text('v4.provenance'), '실제 차량 치수 아님')
    flows.search_and_place(ui, 0)
    wait_axes(ui)
    flows.confirm_and_save(ui)
    flows.restart(ui)
    ui.wait('v0.screen')
    case.contains('V0 복원 후 합성 라벨', ui.text('v0.scene.0.provenance'), '실제 차량 치수 아님')
    flows.open_scene(ui)
    case.contains('V4 복원 후 합성 라벨', ui.text('v4.provenance'), '실제 차량 치수 아님')
    detail(ui)
    case.contains('V5 복원 후 합성 라벨', ui.text('v5.provenance'), '실제 차량 치수 아님')
    case.record['captures'].append(ui.capture('S-PROFILE-02'))


def loading(case, ui):
    case.input(seed=seeded(ui, 'OCCLUSION'))
    flows.open_scene(ui)
    ui.tap('v4.checklist')
    ui.wait('v6.screen')
    case.contains('V6 미판정 고지', ui.text('v6.notAssessed'), '판정하지 않습니다')
    case.check('적재 순서 표시', ui.has('v6.load.0') and ui.has('v6.load.1'), 'present', None)
    ui.tap('v6.load.0.check')
    case.contains('체크 동작', ui.text('v6.load.0.check'), '확인함')
    door = ui.text('v6.door')
    case.input(door=door)
    case.record['captures'].append(ui.capture('V6-checklist'))
    # Real console trunk through the real SDK: an offer only. The saved head must not change.
    with emu_lib.VehicleConsole() as console:
        head_before = ui.dump_state()['draft']['baseRevision']
        case.input(trunk_open=console.put('/vehicle/body?trunk=1'))
        ui.wait('app.offer.checklist', 30)
        case.contains('열림은 제안만', ui.text('app.offer.checklist'), '감지하지 않습니다')
        case.contains('문 상태 열림', ui.text('v6.door'), '열림')
        case.record['captures'].append(ui.capture('V6-door-open'))
        case.input(trunk_close=console.put('/vehicle/body?trunk=0'))
        ui.wait('app.offer.save', 30)
        case.contains('닫힘은 저장 안내만', ui.text('app.offer.save'), '자동으로 저장하지 않았습니다')
        ui.tap('app.offer.dismiss')
        case.equal('문 신호로 저장본 불변', ui.dump_state()['draft']['baseRevision'], head_before)


def _crash_free(case, ui, label):
    case.check(f'{label}: 프로세스 유지', bool(ui.alive()), 'running', ui.alive())
    crash = adb('logcat', '-d', '-b', 'crash').stdout
    case.check(f'{label}: 처리되지 않은 예외 없음', ui.package not in crash, 'no crash', crash[-400:])


def _item_form(ui, name, priority, x=100):
    ui.tap('v2.new')
    ui.wait('v2.form')
    ui.type_text('v2.name', name)
    for tag, value in (('X', x), ('Y', 100), ('Z', 100), ('ERROR', 5), ('PRIORITY', priority)):
        ui.keypad_enter(f'v2.field.{tag}', value)
    ui.tap('v2.confirm')


def input_bounds(case, ui):
    """Boundary values through the real form. A refused value keeps the form and what was typed; the app never exits."""
    # The same case runs on both variants: each run keeps its own captures.
    variant_prefix = 'release-' if case.record.get('variant') == 'release' else ''
    flows.fresh_start(ui)
    adb('logcat', '-b', 'crash', '-c')
    ui.tap('v0.library')
    ui.wait('v2.screen')
    _item_form(ui, 'p100', 100)
    ui.tap('v2.save')
    case.contains('꺼냄순서 100 저장', ui.wait_scrolled('v2.message').text, '저장했습니다')
    _crash_free(case, ui, '꺼냄순서 100')
    _item_form(ui, 'p101', 101)
    ui.tap('v2.save')
    time.sleep(1)
    _crash_free(case, ui, '꺼냄순서 101')
    case.contains('꺼냄순서 101 칸 오류', ui.text('v2.field.PRIORITY'), '0~100')
    case.contains('입력 보존: 값', ui.text('v2.field.PRIORITY'), '101')
    case.contains('입력 보존: 이름', ui.text('v2.name'), 'p101')
    case.contains('저장 안 됨 안내', ui.text('v2.message'), '저장하지 않았습니다')
    case.record['captures'].append(ui.capture(f'{variant_prefix}input-bounds-priority101'))
    ui.keypad_enter('v2.field.PRIORITY', 100)
    ui.tap('v2.save')
    # The list is ordered by random item id, so either row may hold the corrected item.
    ui.wait_scrolled('v2.item.1.title')
    saved = ui.text('v2.item.0.title') + ' | ' + ui.text('v2.item.1.title')
    case.contains('고친 뒤 같은 폼에서 저장', saved, 'p101')
    case.contains('먼저 저장한 물품 유지', saved, 'p100')
    # Name length: the contract allows 120 characters.
    _item_form(ui, 'n' * 120, 1)
    ui.tap('v2.save')
    case.contains('이름 120자 저장', ui.wait_scrolled('v2.message').text, '저장했습니다')
    _crash_free(case, ui, '이름 120자')
    _item_form(ui, 'm' * 121, 1)
    ui.tap('v2.save')
    time.sleep(1)
    _crash_free(case, ui, '이름 121자')
    case.contains('이름 121자 오류', ui.text('v2.name'), '120자까지')
    case.record['captures'].append(ui.capture(f'{variant_prefix}input-bounds-name121'))
    ui.tap('v2.cancel')
    # Repeat spread: the stored error is limited to 1000 mm and must cover the spread.
    flows.go_home(ui)
    ui.tap('v0.profile')
    ui.wait('v1.screen')
    ui.tap('v1.measure')
    ui.wait('v1.form')
    for tag, value in (('X', 1000), ('Y', 800), ('Z', 1000), ('REPEAT_X', 3000), ('REPEAT_Y', 800), ('REPEAT_Z', 1000)):
        ui.keypad_enter(f'v1.field.{tag}', value)
    ui.tap('v1.save')
    time.sleep(1)
    _crash_free(case, ui, '측정 차이 2000')
    case.contains('측정 차이 오류', ui.text('v1.field.REPEAT_X'), '다시 재주세요')
    case.contains('입력 보존: 측정값', ui.text('v1.field.REPEAT_X'), '3000')
    case.record['captures'].append(ui.capture(f'{variant_prefix}input-bounds-spread'))
    ui.keypad_enter('v1.field.REPEAT_X', 2000)
    # Obstacle origin: beyond the coordinate limit is refused, on the limit is stored.
    for tag, value in (('OBSTACLE_X', 100), ('OBSTACLE_Y', 100), ('OBSTACLE_Z', 100), ('OBSTACLE_OX', 10001)):
        ui.keypad_enter(f'v1.field.{tag}', value)
    ui.tap('v1.save')
    time.sleep(1)
    _crash_free(case, ui, '장애물 좌표 10001')
    case.contains('장애물 좌표 오류', ui.text('v1.field.OBSTACLE_OX'), '0~10000')
    ui.keypad_enter('v1.field.OBSTACLE_OX', 10000)
    ui.tap('v1.save')
    row = ui.wait_scrolled('v1.row.0').text
    _crash_free(case, ui, '경계값 저장')
    case.contains('차이 1000은 오차 1000으로 저장', row, '±1000')
    case.contains('반복 실측 등급', row, '반복 실측')
    case.record['captures'].append(ui.capture(f'{variant_prefix}input-bounds-profile-saved'))
