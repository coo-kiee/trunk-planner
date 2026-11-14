"""The continuous user scenario. Same steps for both variants, entered only through the screen."""
import time
import flows
from ui_driver import StepFailed, adb, emu_lib


def pose_of(state, instance_id):
    row = next((i for i in state['draft']['instances'] if i['id'] == instance_id), None)
    return None if row is None else (row['x'], row['y'], row['z'], row['o'])


def wait_axes(ui, screen='v4', timeout=30):
    """Waits for a judgement on the current draft, then returns each axis code from the screen."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        code = ui.text(f'{screen}.axis.placement.code')
        if code not in ('NOT_JUDGED', 'STALE'):
            break
        time.sleep(0.5)
    return {axis: ui.text(f'{screen}.axis.{axis}.code') for axis in ('placement', 'opening', 'path', 'handling', 'search')}


def core(case, ui, capture_prefix):
    # Product 1: direct entry on a stopped vehicle, measured space, candidate, manual adjustment, confirm, explicit save.
    flows.fresh_start(ui)
    flows.add_item(ui, 'suitcase-1', 400, 300, 500, error=5, priority=2)
    case.contains('제품1 보관함 저장', ui.text('v2.item.0'), '400×300×500')
    case.contains('확인된 치수만 저장', ui.text('v2.item.0'), '±5')
    flows.go_home(ui)
    flows.create_measured_profile(ui, 1400, 800, 1200, opening=(1300, 750), error=10)
    case.contains('직접측정 등급 표시', ui.text('v1.row.0.grade'), '사용자 직접측정')
    ui.tap('v1.row.0.start')
    ui.wait('v4.screen')
    flows.search_and_place(ui, 0)
    case.record['captures'].append(ui.capture(f'{capture_prefix}-product1-candidate'))
    axes = wait_axes(ui)
    case.input(product1_axes_first=axes)
    ui.tap('v4.step.100')
    ui.tap('v4.move.VEHICLE_LEFT')
    case.contains('왼쪽 이동 문구', ui.text('v4.lastMove'), '차량 왼쪽으로 100mm')
    wait_axes(ui)
    ui.tap('v4.lock')
    case.contains('고정 표시', ui.text('v4.selected.lock'), '고정됨')
    case.contains('고정이 안전 보증이 아님', ui.text('v4.selected.lock'), '안전 보증이 아닙니다')
    flows.confirm_and_save(ui)
    case.contains('명시 저장 후 저장본', ui.text('v4.saved'), 'r1')
    state = ui.dump_state()
    product1 = state['draft']['instances'][0]['id']
    pose1 = pose_of(state, product1)
    case.input(product1_id=product1, product1_pose=pose1)
    case.record['captures'].append(ui.capture(f'{capture_prefix}-product1-saved'))

    # A real kill: no saved state survives force-stop, so what comes back can only come from Room.
    ui.force_stop()
    case.equal('프로세스 종료 확인', ui.alive(), '')
    ui.start()
    ui.wait('v0.screen')
    case.contains('목록은 저장 계획이라고 표시', ui.text('v0.planNotice'), '감지한 결과가 아닙니다')
    case.contains('복원 목록 저장본', ui.text('v0.scene.0.title'), 'r1')
    flows.open_scene(ui)
    ui.tap('v4.instance.0')
    state = ui.dump_state()
    case.equal('재시작 후 제품1 좌표 동일', pose_of(state, product1), pose1)
    case.equal('재시작 후 제품1 고정 유지', state['draft']['instances'][0]['locked'], True)
    case.contains('복원 판정은 기록 라벨', ui.text('v4.restoredRecord'), '저장 시 판정 기록')
    case.record['captures'].append(ui.capture(f'{capture_prefix}-restored'))

    # Product 2: only the new item is searched. The fixed item must not move through any of this.
    flows.go_home(ui)
    flows.add_item(ui, 'box-2', 300, 200, 300, error=5, priority=1)
    flows.open_scene(ui)
    row = next(i for i in range(4) if 'box-2' in ui.text(f'v4.add.{i}'))
    flows.search_and_place(ui, row)
    state = ui.dump_state()
    case.equal('탐색 후 제품1 좌표 불변', pose_of(state, product1), pose1)
    axes = wait_axes(ui)
    case.input(both_axes=axes)
    state = ui.dump_state()
    product2 = next(i['id'] for i in state['draft']['instances'] if i['id'] != product1)
    case.check('제품2가 제품1과 별개 위치', pose_of(state, product2)[:3] != pose1[:3], 'different', pose_of(state, product2))
    ui.tap('v4.lock')
    flows.confirm_and_save(ui)
    case.contains('제품2 명시 저장 r2', ui.text('v4.saved'), 'r2')
    state = ui.dump_state()
    case.equal('저장 후 제품1 좌표 불변', pose_of(state, product1), pose1)
    case.equal('동시 적재 2개', len(state['draft']['instances']), 2)
    pose2 = pose_of(state, product2)

    # Both together, read back on the detail screen after one more kill.
    ui.force_stop()
    ui.start()
    ui.wait('v0.screen')
    case.contains('재시작 목록 r2·2개', ui.text('v0.scene.0.title'), '짐 2개')
    flows.open_scene(ui)
    state = ui.dump_state()
    case.equal('두 번째 재시작 후 제품1 좌표', pose_of(state, product1), pose1)
    case.equal('두 번째 재시작 후 제품2 좌표', pose_of(state, product2), pose2)
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    ui.tap('v5.validate')
    time.sleep(2)
    codes = {axis: ui.text(f'v5.axis.{axis}.code') for axis in ('placement', 'opening', 'path', 'handling', 'search', 'safety')}
    case.input(simultaneous_axes=codes)
    case.equal('동시 판정 내부 CLEAR', codes['placement'], 'CLEAR')
    case.equal('동시 판정 입구 FITS', codes['opening'], 'FITS')
    case.equal('복원 뒤 경로를 통과로 승격하지 않음', codes['path'], 'UNVERIFIED')
    case.equal('취급 위반 없음', codes['handling'], 'OK')
    case.contains('안전 미판정 라벨', ui.text('v5.axis.safety'), '판정하지 않음')
    case.record['captures'].append(ui.capture(f'{capture_prefix}-both-judgement'))
    return product1, pose1


def drive(console):
    return console.put('/vehicle/gear?gear=Drive'), console.put('/vehicle/controls?speed=40&accelerator=30&brake=0')


def park(console):
    return console.put('/vehicle/controls?speed=0&accelerator=0&brake=0'), console.put('/vehicle/gear?gear=Park')


def wait_ux(ui, restricted, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = ui.dump_state()
        if ('requiresDistractionOptimization=true' in state['ux']) == restricted and 'Known' in state['ux']:
            return state
        time.sleep(1)
    raise StepFailed(f'UX state did not become restricted={restricted}: {ui.dump_state()["ux"]}')


def driving_restriction(case, ui, capture_prefix):
    """Real console gear and speed through the real UX restriction listener, with the editor open and a live draft."""
    if not ui.has('v4.screen'):
        flows.open_scene(ui)
    ui.tap('v4.instance.1')
    ui.tap('v4.cam.yawPlus')
    time.sleep(1)
    before = ui.dump_state()
    with emu_lib.VehicleConsole() as console:
        case.input(console_drive=drive(console))
        during = wait_ux(ui, True)
        time.sleep(2)
        frames_a = ui.dump_state()['render']['framesDrawn']
        nodes = ui.nodes()
        for tag in ('v4.move.VEHICLE_LEFT', 'v4.save', 'v4.confirm', 'v4.gl', 'v4.undo', 'v4.add'):
            case.check(f'주행 중 {tag} 없음', ui.find(tag, nodes) is None, 'absent', 'present' if ui.find(tag, nodes) else 'absent')
        case.check('주행 중 제한 안내 표시', ui.find('v4.restricted', nodes) is not None, 'present', None)
        case.record['captures'].append(ui.capture(f'{capture_prefix}-driving'))
        time.sleep(5)
        after = ui.dump_state()
        case.equal('주행 중 렌더 프레임 증가 0', after['render']['framesDrawn'] - frames_a, 0)
        case.equal('주행 중 렌더 게이트 닫힘', after['render']['gateOpen'], False)
        case.input(gate_reason_driving=after['render']['gateReason'], ux_during=during['ux'])
        case.input(console_park=park(console))
        wait_ux(ui, False)
        ui.wait('v4.screen')
        time.sleep(2)
        restored = ui.dump_state()
        case.equal('정차 복귀 후 같은 초안', restored['draft']['instances'], before['draft']['instances'])
        case.equal('정차 복귀 후 같은 카메라', restored['draft']['camera'], before['draft']['camera'])
        case.equal('정차 복귀 후 같은 선택', restored['draft']['selected'], before['draft']['selected'])
        case.equal('정차 복귀 후 같은 undo/redo', (restored['draft']['undo'], restored['draft']['redo']), (before['draft']['undo'], before['draft']['redo']))
        case.equal('정차 복귀 후 렌더 게이트 열림', restored['render']['gateOpen'], True)
        case.record['captures'].append(ui.capture(f'{capture_prefix}-parked'))
        # The reading screens as well: V5 keeps its text and drops every action while driving.
        ui.tap('v4.detail')
        ui.wait('v5.screen')
        case.check('정차 중 V5 재검증 표시', ui.has('v5.validate') or ui.scroll_to('v5.validate') is not None, 'present', None)
        draft = ui.dump_state()['draft']
        code = ui.text('v5.axis.placement.code')
        case.input(v5_drive=drive(console))
        wait_ux(ui, True)
        time.sleep(2)
        nodes = ui.nodes()
        case.check('주행 중 V5 재검증 없음', ui.find('v5.validate', nodes) is None, 'absent', None)
        case.check('주행 중 V5 제한 안내', ui.find('v5.restricted', nodes) is not None, 'present', None)
        case.check('주행 중 V5 판정 읽기 유지', ui.find('v5.axes', nodes) is not None, 'present', None)
        case.record['captures'].append(ui.capture(f'{capture_prefix}-v5-driving'))
        case.equal('주행 중 초안 불변', ui.dump_state()['draft']['instances'], draft['instances'])
        case.input(v5_park=park(console))
        wait_ux(ui, False)
        ui.wait_scrolled('v5.validate')
        case.equal('정차 후 같은 초안', ui.dump_state()['draft']['instances'], draft['instances'])
        case.equal('정차 후 같은 판정', ui.text('v5.axis.placement.code'), code)
        ui.tap('app.back')
        ui.wait('v4.screen')


def recreate(case, ui, capture_prefix):
    """Edit, undo, camera; then the process really dies and everything is read back from the draft transaction."""
    if not ui.has('v4.screen'):
        flows.open_scene(ui)
    ui.tap('v4.instance.1')
    if ui.find('v4.lock').text.startswith('고정 해제'):
        ui.tap('v4.lock')
    ui.tap('v4.step.10')
    ui.tap('v4.move.VEHICLE_RIGHT')
    time.sleep(1)
    ui.tap('v4.move.VEHICLE_RIGHT')
    time.sleep(1)
    ui.tap('v4.undo')
    time.sleep(1)
    ui.tap('v4.cam.yawPlus')
    ui.tap('v4.cam.closer')
    time.sleep(1)
    before = ui.dump_state()
    case.check('되돌리기·다시실행 스택이 비어있지 않음', before['draft']['undo'] > 0 and before['draft']['redo'] > 0, '>0', (before['draft']['undo'], before['draft']['redo']))
    ui.force_stop()
    case.equal('강제 종료 확인', ui.alive(), '')
    ui.start()
    ui.wait('v0.screen')
    case.contains('초안 있음 표시', ui.text('v0.scene.0.draft'), '저장하지 않은 편집 초안')
    case.contains('초안 이어하기 버튼', ui.text('v0.scene.0.open'), '초안 이어하기')
    flows.open_scene(ui)
    time.sleep(1)
    after = ui.dump_state()
    for key in ('instances', 'camera', 'undo', 'redo', 'baseRevision'):
        case.equal(f'프로세스 재생성 후 {key}', after['draft'][key], before['draft'][key])
    case.contains('초안 복원 안내', ui.text('v4.restoredDraft'), '복원')
    ui.tap('v4.redo')
    time.sleep(1)
    case.equal('복원된 redo 실제 동작', ui.dump_state()['draft']['redo'], before['draft']['redo'] - 1)
    case.record['captures'].append(ui.capture(f'{capture_prefix}-recreated'))
