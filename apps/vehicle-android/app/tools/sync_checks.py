"""Pairing and inbound decisions on the real vehicle screen with the real relay and a real local PostgreSQL.

The phone side here is a plain HTTP client of the relay's documented phone API. It is not the phone web app and proves
nothing about it. Real phone and browser results belong to the phone-web and e2e owners.
"""
import json, os, select, signal, subprocess, tempfile, time, urllib.error, urllib.request, uuid
from pathlib import Path
import flows
from core_flow import wait_axes
from ui_driver import EVIDENCE, LOGS, ROOT, StepFailed, adb, emu_lib

RELAY_DIR = ROOT / 'services/relay'
RELAY_PORT, PHONE_PORT = 23287, 23288
GRADE = 'emulator:real_relay_local_postgres+http_phone_client(not_phone_web)'
T = '2026-11-04T00:00:00Z'


class LocalRelay:
    """The relay owner's isolated stack. It removes its own PostgreSQL when it stops."""

    def __init__(self, config_file=None):
        self.config_file = config_file

    def __enter__(self):
        env = {**emu_lib.ENV, 'RELAY_PORT': str(RELAY_PORT), 'VEHICLE_RELAY_BASE_URL': f'http://10.0.2.2:{RELAY_PORT}',
               'PHONE_RELAY_BASE_URL': f'http://10.0.2.2:{RELAY_PORT}', 'PHONE_WEB_BASE_URL': f'http://10.0.2.2:{PHONE_PORT}/'}
        env.pop('JAVA_TOOL_OPTIONS', None)
        if self.config_file:
            env['RELAY_CONFIG_FILE'] = str(self.config_file)
        LOGS.mkdir(parents=True, exist_ok=True)
        self.log = (LOGS / 'ui-local-stack.log').open('a')
        self.process = subprocess.Popen(['node', 'test/support/local-stack.ts'], cwd=RELAY_DIR, env=env, stdout=subprocess.PIPE, stderr=self.log, text=True)
        deadline = time.time() + 180
        while time.time() < deadline:
            ready, _, _ = select.select([self.process.stdout], [], [], 2)
            if ready:
                line = self.process.stdout.readline()
                if 'local_stack_ready' in line:
                    self.info = json.loads(line)
                    return self
            if self.process.poll() is not None:
                break
        raise StepFailed('local relay stack did not start; see logs/ui-local-stack.log')

    def stop(self):
        if self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()

    def __exit__(self, *_):
        self.stop()
        self.log.close()


class PhoneClient:
    """HTTP only, from the host. Holds its token in memory and never writes it to evidence."""

    def __init__(self):
        self.base = f'http://127.0.0.1:{RELAY_PORT}'
        self.token = None
        self.pairing = None

    def request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        if data:
            request.add_header('Content-Type', 'application/json')
        if self.token:
            request.add_header('Authorization', f'Bearer {self.token}')
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, json.loads(response.read() or b'{}')
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b'{}')

    def claim(self, qr_text):
        token = qr_text.split('#pairing=', 1)[1]
        status, body = self.request('POST', '/phone/v1/pairing/claim', {'pairingToken': token})
        if status != 200:
            raise StepFailed(f'claim {status} {body.get("error", {}).get("code")}')
        self.token, self.pairing = body['phoneToken'], body['pairingId']
        return status

    def draft(self, name, x, client_revision=1, draft_id=None, base=None, payload_id=None):
        payload_id = payload_id or (base[0] if base else str(uuid.uuid4()))
        draft = {
            'draftId': draft_id or str(uuid.uuid4()), 'pairingId': self.pairing, 'schemaVersion': 1, 'clientRevision': client_revision,
            'payload': {
                'schemaVersion': 1, 'id': payload_id, 'revision': 1, 'createdAt': T, 'updatedAt': T, 'name': name, 'shapeKind': 'BOX',
                'shapeProxy': [{'origin': {'x': 0, 'y': 0, 'z': 0}, 'size': {'x': x, 'y': 200, 'z': 300}}],
                'measuredDimensionsMm': {'x': x, 'y': 200, 'z': 300}, 'errorMm': 5, 'measurementSource': 'USER_TAPE',
                'rotationRule': 'ANY24', 'stackable': True, 'removalPriority': 1, 'confirmedDimensions': True,
            },
        }
        if base:
            draft['baseItemId'], draft['baseItemRevision'] = base
        return draft

    def submit(self, draft):
        return self.request('POST', '/phone/v1/drafts', {'consent': {'consentVersion': 'transfer-consent-v1', 'dimensionsConsent': True, 'photoConsent': False}, 'draft': draft})

    def status(self, draft):
        return self.request('GET', f"/phone/v1/drafts/{draft['draftId']}/{draft['clientRevision']}")


def decode_qr(ui):
    """Reads the code from the pixels on the display. The app's token never leaves through any other route."""
    width, height, rgba = ui.raw_screen()
    with tempfile.NamedTemporaryFile(dir=LOGS, suffix='.rgba', delete=False) as handle:
        handle.write(rgba)
    try:
        out = subprocess.run(['node', str(Path(__file__).parent / 'decode_qr.cjs'), handle.name, str(width), str(height), str(ROOT / 'apps/phone-web')], capture_output=True, text=True, timeout=60)
    finally:
        os.unlink(handle.name)
    if not out.stdout:
        raise StepFailed(f'QR not decodable: {out.stderr[-300:]}')
    return out.stdout


def connected(ui):
    """Real pairing through the screen and a real decoded QR."""
    flows.fresh_start(ui)
    ui.set_flag('relay_url', f'http://10.0.2.2:{RELAY_PORT}')
    ui.set_flag('phone_url', f'http://10.0.2.2:{PHONE_PORT}/')
    flows.restart(ui)
    ui.wait('v0.screen')
    ui.tap('v0.pairing')
    ui.wait('v3.screen')
    ui.tap('v3.start')
    ui.wait_scrolled('v3.qr', 30)
    phone = PhoneClient()
    phone.claim(decode_qr(ui))
    return phone


def wait_code(ui, expected, timeout=30):
    deadline = time.time() + timeout
    shown = None
    while time.time() < deadline:
        shown = ui.text('v3.statusCode')
        if expected in shown:
            return shown
        time.sleep(1)
    raise StepFailed(f'status {shown}, wanted {expected}')


def wait_draft_row(ui, timeout=30):
    return ui.wait_scrolled('v3.draft.0.title', timeout)


def wait_phone_state(phone, draft, expected, timeout=30):
    deadline = time.time() + timeout
    body = None
    while time.time() < deadline:
        _, body = phone.status(draft)
        if body.get('state') == expected:
            return body
        time.sleep(1)
    raise StepFailed(f'phone sees {body}, wanted {expected}')


def library_titles(ui):
    flows.go_home(ui)
    ui.tap('v0.library')
    ui.wait('v2.screen')
    titles = []
    for i in range(6):
        node = ui.find(f'v2.item.{i}.title') or (ui.scroll_to(f'v2.item.{i}.title', attempts=2) if ui.find('v2.item.0') and i == 0 else None)
        if node is None:
            break
        titles.append(node.text)
    return titles


def ce18(case, ui):
    with LocalRelay() as stack:
        case.input(relay=stack.info['grade'], phone_client='HTTP only, not the phone web app')
        phone = connected(ui)
        case.contains('연결 유효 표시', wait_code(ui, 'PAIRED'), 'PAIRED')
        case.contains('QR 만료 안내', ui.text('v3.qrExpiry'), 'QR 만료까지')
        case.record['captures'].append(ui.capture('CE-18-04-paired'))
        draft = phone.draft('phone-box', 410)
        status, receipt = phone.submit(draft)
        case.equal('폰 제출 수신', (status, receipt.get('state')), (201, 'PENDING'))
        title = wait_draft_row(ui)
        case.contains('수신 초안 표시', title.text, 'phone-box')
        case.contains('수신은 반영이 아님', ui.text('v3.draft.0.pending'), '아직 차량에 반영되지 않음')
        case.record['captures'].append(ui.capture('CE-18-04-pending'))
        ui.tap('v3.draft.0.apply')
        case.contains('수락 후 차량 반영 revision', ui.wait_scrolled('v3.decision.0.outcome').text, '차량 반영: 물품 r1')
        terminal = wait_phone_state(phone, draft, 'ACCEPTED')
        case.equal('폰에 전달된 acceptedRevision', terminal.get('terminalAck', {}).get('acceptedRevision'), 1)
        time.sleep(2)
        case.contains('전달 확인 표시', ui.text('v3.decision.0.delivery'), '릴레이가 수신함')
        case.record['captures'].append(ui.capture('CE-18-04-accepted'))
        # Same draft again: the relay answers duplicate, and the vehicle must not add anything.
        status, again = phone.submit(draft)
        case.equal('같은 초안 재전송은 중복', (status, again.get('duplicate')), (200, True))
        time.sleep(8)
        case.contains('중복 재전송 후 대기 없음', ui.text('v3.inboxEmpty'), '없음')
        titles = library_titles(ui)
        case.equal('중복 뒤 보관함 r1 한 건', [t for t in titles if 'phone-box' in t and 'r1' in t].__len__(), 1)
        case.check('r2가 생기지 않음', not any('r2' in t for t in titles), 'no r2', titles)
        # Revoke while a draft waits: its content stays visible and it cannot be accepted.
        flows.go_home(ui)
        ui.tap('v0.pairing')
        ui.wait('v3.screen')
        waiting = phone.draft('late-box', 420)
        phone.submit(waiting)
        wait_draft_row(ui)
        ui.tap('v3.revoke')
        wait_code(ui, 'REVOKED')
        case.contains('해제 안내', ui.text('v3.status'), '해제')
        case.contains('해제 후 초안 보존·수락 불가', ui.wait_scrolled('v3.draft.0.inactive').text, '수락할 수 없으며')
        case.check('해제 후 수락 버튼 없음', not ui.has('v3.draft.0.apply'), 'absent', ui.has('v3.draft.0.apply'))
        case.record['captures'].append(ui.capture('CE-18-04-revoked'))
        status, refused = phone.submit(phone.draft('after-revoke', 430))
        case.equal('해제 후 재전송 거부', (status, refused.get('error', {}).get('code')), (403, 'PAIRING_REVOKED'))
    # Expiry: real relay with a short credential lifetime on its demo-only test profile.
    source = json.loads((RELAY_DIR / 'config/relay-config-v1.json').read_text())
    short = {**source, 'relayConfigVersion': 9001, 'profile': 'test', 'credentialTtlSeconds': 75}
    path = LOGS / 'relay-config-short-ttl.json'
    path.write_text(json.dumps(short))
    with LocalRelay(path) as stack:
        case.input(expiry_relay=stack.info['grade'], expiry_config={'credentialTtlSeconds': 75, 'profile': 'test'})
        phone = connected(ui)
        wait_code(ui, 'PAIRED')
        pending = phone.draft('expiring-box', 440)
        phone.submit(pending)
        wait_draft_row(ui)
        wait_code(ui, 'EXPIRED', timeout=120)
        case.contains('만료 안내', ui.text('v3.status'), '만료')
        case.contains('만료 후 초안 수락 불가', ui.wait_scrolled('v3.draft.0.inactive').text, '수락할 수 없으며')
        case.record['captures'].append(ui.capture('CE-18-04-expired'))


def ce19(case, ui):
    with LocalRelay() as stack:
        case.input(relay=stack.info['grade'], phone_client='HTTP only, not the phone web app')
        phone = connected(ui)
        wait_code(ui, 'PAIRED')
        first = phone.draft('shared', 300)
        phone.submit(first)
        wait_draft_row(ui)
        ui.tap('v3.draft.0.apply')
        ui.wait_scrolled('v3.decision.0.outcome')
        accepted = wait_phone_state(phone, first, 'ACCEPTED')['terminalAck']
        item = accepted['acceptedItemId']
        # Vehicle edits the same item while the phone still bases on r1.
        flows.go_home(ui)
        ui.tap('v0.library')
        ui.wait('v2.screen')
        ui.tap('v2.item.0.newVersion')
        ui.wait('v2.form')
        ui.keypad_enter('v2.field.X', 350)
        ui.tap('v2.confirm')
        ui.tap('v2.save')
        ui.wait_scrolled('v2.message')
        case.contains('차량 측 r2', ui.text('v2.item.0.title'), 'r2')
        flows.go_home(ui)
        ui.tap('v0.pairing')
        ui.wait('v3.screen')
        # 1. Keep the vehicle's version.
        keep = phone.draft('shared-phone-keep', 500, base=(item, 1), draft_id=str(uuid.uuid4()))
        phone.submit(keep)
        wait_draft_row(ui)
        case.contains('폰 기준 revision 표시', ui.text('v3.draft.0.pending'), '물품 r1')
        ui.tap('v3.draft.0.apply')
        conflict = ui.wait_scrolled('v3.draft.0.conflict').text
        case.contains('충돌 사용자 선택 요구', conflict, '직접 선택')
        case.contains('차량 현재 revision 표시', conflict, '차량은 r2')
        case.record['captures'].append(ui.capture('CE-19-03-choice'))
        for tag in ('overwrite', 'keepExisting', 'separate'):
            case.check(f'선택지 {tag}', ui.wait_scrolled(f'v3.draft.0.{tag}') is not None, 'present', None)
        conflict_seen = wait_phone_state(phone, keep, 'CONFLICT_REPORTED')
        case.equal('폰에 충돌 revision 전달', conflict_seen.get('conflict', {}).get('authoritativeRevision'), 2)
        ui.tap('v3.draft.0.keepExisting')
        rejected = wait_phone_state(phone, keep, 'REJECTED')
        case.equal('기존 유지는 거절 전달', rejected.get('terminalAck', {}).get('rejectCode'), 'USER_KEPT_VEHICLE_VERSION')
        case.contains('기존 유지 결과 표시', ui.wait_scrolled('v3.decision.1.outcome').text, '기존 버전 유지')
        # 2. Apply as a new version, shown after the conflict.
        apply_new = phone.draft('shared-phone-apply', 510, base=(item, 1))
        phone.submit(apply_new)
        wait_draft_row(ui)
        ui.tap('v3.draft.0.apply')
        ui.wait_scrolled('v3.draft.0.overwrite')
        ui.tap('v3.draft.0.overwrite')
        applied = wait_phone_state(phone, apply_new, 'ACCEPTED')['terminalAck']
        case.equal('새 버전 적용 r3', applied.get('acceptedRevision'), 3)
        # 3. Separate item.
        separate = phone.draft('shared-phone-separate', 520, base=(item, 1))
        phone.submit(separate)
        wait_draft_row(ui)
        ui.tap('v3.draft.0.apply')
        ui.wait_scrolled('v3.draft.0.separate')
        ui.tap('v3.draft.0.separate')
        split = wait_phone_state(phone, separate, 'ACCEPTED')['terminalAck']
        case.check('별도 물품은 다른 id', split.get('acceptedItemId') != item, f'!= {item}', split.get('acceptedItemId'))
        case.equal('별도 물품 r1', split.get('acceptedRevision'), 1)
        case.record['captures'].append(ui.capture('CE-19-03-decisions'))
        titles = library_titles(ui)
        case.check('조용한 덮어쓰기 없음: 공유 물품 r3·별도 r1 공존', any('r3' in t for t in titles) and any('r1' in t for t in titles), 'r3 and r1', titles)
        case.record['captures'].append(ui.capture('CE-19-03-library'))
        # The offline vehicle case: a draft waiting at the relay is handled when the vehicle comes back.
        ui.force_stop()
        waiting = phone.draft('while-away', 530)
        phone.submit(waiting)
        ui.start()
        ui.wait('v0.screen')
        ui.tap('v0.pairing')
        ui.wait('v3.screen')
        case.contains('복귀 후 대기 초안 수신', wait_draft_row(ui, 40).text, 'while-away')
        ui.tap('v3.draft.0.keep')
        case.equal('복귀 후 사용자 결정 전달', wait_phone_state(phone, waiting, 'REJECTED').get('terminalAck', {}).get('rejectCode'), 'USER_DISCARDED')


def ce20(case, ui):
    with LocalRelay() as stack:
        case.input(relay=stack.info['grade'])
        phone = connected(ui)
        wait_code(ui, 'PAIRED')
        stack.stop()
    wait_code(ui, 'NETWORK_UNAVAILABLE', timeout=120)
    case.contains('릴레이 중단 안내', ui.text('v3.status'), '차량 단독 기능은 계속')
    case.record['captures'].append(ui.capture('CE-20-03-relay-down'))
    # The whole vehicle-alone path with no relay behind it.
    flows.go_home(ui)
    flows.add_item(ui, 'offline-1', 300, 200, 300)
    flows.go_home(ui)
    flows.create_demo_profile(ui)
    ui.tap('v1.row.0.start')
    ui.wait('v4.screen')
    flows.search_and_place(ui, 0)
    wait_axes(ui)
    ui.tap('v4.lock')
    flows.confirm_and_save(ui)
    state = ui.dump_state()
    first = state['draft']['instances'][0]
    case.contains('릴레이 없이 r1 저장', ui.text('v4.saved'), 'r1')
    ui.force_stop()
    ui.start()
    ui.wait('v0.screen')
    flows.add_item(ui, 'offline-2', 200, 200, 200)
    flows.open_scene(ui)
    row = next(i for i in range(4) if 'offline-2' in ui.text(f'v4.add.{i}'))
    flows.search_and_place(ui, row)
    wait_axes(ui)
    flows.confirm_and_save(ui)
    case.contains('릴레이 없이 r2 저장', ui.text('v4.saved'), 'r2')
    state = ui.dump_state()
    kept = next(i for i in state['draft']['instances'] if i['id'] == first['id'])
    case.equal('릴레이 없이 고정짐 불변', (kept['x'], kept['y'], kept['z']), (first['x'], first['y'], first['z']))
    case.equal('릴레이 없이 2개 적재', len(state['draft']['instances']), 2)
    case.record['captures'].append(ui.capture('CE-20-03-saved'))
    case.input(phone_outbox='폰 전송함 대기 표시는 phone-web/e2e 담당. 여기서는 시험하지 않음')


CASES = {
    'CE-18-04': ('수신승인·중복반영 방지·만료/해제 안내', GRADE, ce18),
    'CE-19-03': ('충돌 사용자 선택과 pending 대 수락 revision', GRADE, ce19),
    'CE-20-03': ('릴레이 중단 시 차량 단독 핵심 흐름', GRADE, ce20),
}
