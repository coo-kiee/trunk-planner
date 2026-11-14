"""What the running app actually paints, per screen, with product tokens and then with a test override.

Reads real screen pixels and node bounds plus the renderer's real palette and framebuffer. The override is a flag file
in the app's own sandbox; packages/design-tokens is hashed before and after to show it was not touched.
"""
import hashlib, json, re, time
import flows
from core_flow import wait_axes
from ui_driver import EVIDENCE, ROOT, adb

TOKENS = ROOT / 'packages/design-tokens/kotlin/ai/cargo/tokens/CargoTokens.kt'
OVERRIDE = {
    'clear': '#FF00FF', 'conflict': '#00FF00', 'uncertain': '#00FFFF', 'unverified': '#FFFF00', 'stale': '#FF8000',
    'accent': '#FF0000', 'surface': '#333333', 'background': '#222222', 'item': '#112233', 'selected': '#445566',
}
OVERRIDE_SIZES = {'spacingLarge': 40, 'typeBody': 30}


def product_tokens():
    """Token name to "#RRGGBB", read from the generated source rather than repeated here."""
    found = re.findall(r'color(\w+): Long = 0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})L', TOKENS.read_text())
    return {name[0].lower() + name[1:]: '#' + value.upper() for name, value in found}


class Sampler:
    def __init__(self, ui, flag):
        self.ui = ui
        self.flag = flag
        self.samples = []

    def scene(self, *seeds, ready='v0.screen'):
        ui = self.ui
        flows.fresh_start(ui)
        if self.flag:
            ui.set_flag('theme', self.flag)
        for seed in seeds:
            ui.seed(seed)
        flows.restart(ui)
        ui.wait(ready)

    def status(self, screen, tag, kind):
        node = self.ui.scroll_to(tag)
        pixel = self.ui.pixel(node.left + 16, (node.top + node.bottom) // 2)
        self.samples.append({'screen': screen, 'tag': tag, 'component': 'CargoStatus', 'token': kind, 'pixel': pixel, 'bounds': [node.left, node.top, node.right, node.bottom], 'text': node.text[:80]})

    def button(self, screen, tag, token):
        node = self.ui.scroll_to(tag)
        pixel = self.ui.pixel(node.left + 3, node.top + 3)
        self.samples.append({'screen': screen, 'tag': tag, 'component': 'CargoButton', 'token': token, 'pixel': pixel, 'bounds': [node.left, node.top, node.right, node.bottom], 'heightPx': node.bottom - node.top})

    def panel(self, screen, tag):
        node = self.ui.scroll_to(tag)
        pixel = self.ui.pixel(node.left + 3, node.top + 3)
        self.samples.append({'screen': screen, 'tag': tag, 'component': 'CargoPanel', 'token': 'surface', 'pixel': pixel, 'bounds': [node.left, node.top, node.right, node.bottom]})

    def field(self, screen, tag):
        node = self.ui.scroll_to(tag)
        pixel = self.ui.pixel(node.left + 3, node.bottom - 3)
        self.samples.append({'screen': screen, 'tag': tag, 'component': 'CargoField', 'token': 'background', 'pixel': pixel, 'bounds': [node.left, node.top, node.right, node.bottom]})

    def text_height(self, screen, tag):
        node = self.ui.scroll_to(tag)
        self.samples.append({'screen': screen, 'tag': tag, 'component': 'typography', 'token': 'typeBody', 'heightPx': node.bottom - node.top})

    def shot(self, name):
        return self.ui.capture(f'ds-{"override-" if self.flag else ""}{name}')


def collect(ui, flag):
    s = Sampler(ui, flag)
    gl = {}
    # V0, V1, V2
    s.scene('BASIC')
    s.button('V0', 'v0.profile', 'accent')
    s.button('V0', 'v0.library', 'background')
    s.panel('V0', 'v0.scenes')
    s.text_height('V0', 'v0.planNotice')
    s.status('V0', 'v0.door', 'clear')
    s.shot('v0')
    ui.tap('v0.profile')
    ui.wait('v1.screen')
    s.button('V1', 'v1.measure', 'accent')
    s.button('V1', 'v1.demo', 'background')
    s.status('V1', 'v1.row.0.grade', 'unverified')
    ui.tap('v1.measure')
    ui.wait('v1.form')
    s.field('V1', 'v1.field.X')
    s.panel('V1', 'v1.form')
    s.shot('v1')
    flows.go_home(ui)
    ui.tap('v0.library')
    ui.wait('v2.screen')
    ui.tap('v2.new')
    ui.wait('v2.form')
    s.field('V2', 'v2.field.X')
    s.button('V2', 'v2.save', 'accent')
    s.button('V2', 'v2.cancel', 'background')
    s.panel('V2', 'v2.form')
    ui.tap('v2.save')
    s.status('V2', 'v2.unconfirmed', 'unverified')
    s.shot('v2')
    # V3
    flows.go_home(ui)
    ui.tap('v0.pairing')
    ui.wait('v3.screen')
    s.button('V3', 'v3.start', 'accent')
    s.status('V3', 'v3.status', 'unverified')
    s.panel('V3', 'v3.inbox')
    s.shot('v3')
    # V4, V5 CLEAR with the renderer's selected and item colors
    flows.go_home(ui)
    flows.open_scene(ui)
    s.status('V4', 'v4.provenance', 'unverified')
    s.status('V4', 'v4.axis.placement.status', 'clear')
    s.button('V4', 'v4.detail', 'background')
    s.panel('V4', 'v4.judgement')
    ui.tap('v4.cam.opening')
    time.sleep(1)
    gl['item'] = probe_instance(ui, 0)
    ui.tap('v4.instance.0')
    time.sleep(1)
    gl['selected'] = probe_instance(ui, 0)
    gl['palette'] = ui.dump_state()['render']['palette']
    ui.tap('v4.poseEntry')
    s.field('V4', 'v4.pose.X')
    ui.tap('v4.pose.cancel')
    s.shot('v4')
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    s.status('V5', 'v5.axis.placement.status', 'clear')
    s.status('V5', 'v5.axis.safety.status', 'unverified')
    s.button('V5', 'v5.validate', 'background')
    s.panel('V5', 'v5.axes')
    s.shot('v5-clear')
    # V6
    ui.tap('app.back')
    ui.wait('v4.screen')
    ui.tap('v4.checklist')
    ui.wait('v6.screen')
    s.status('V6', 'v6.door', 'clear')
    s.button('V6', 'v6.load.0.check', 'background')
    s.panel('V6', 'v6.load')
    s.shot('v6')
    # CONFLICT on the axis row and on the 3D box
    s.scene('CE03A')
    flows.open_scene(ui)
    ui.tap('v4.validate')
    time.sleep(2)
    s.status('V4', 'v4.axis.placement.status', 'conflict')
    ui.tap('v4.cam.opening')
    time.sleep(1)
    gl['conflict'] = probe_role(ui, 'CONFLICT')
    s.shot('v4-conflict')
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    s.status('V5', 'v5.axis.placement.status', 'conflict')
    s.shot('v5-conflict')
    # STALE on the axis row, UNVERIFIED on the 3D box: no current judgement stands behind the drawn item
    s.scene('CE11ENGINE')
    flows.open_scene(ui)
    s.status('V4', 'v4.axis.placement.status', 'stale')
    ui.tap('v4.cam.opening')
    time.sleep(1)
    gl['unverified'] = probe_role(ui, 'UNVERIFIED')
    s.shot('v4-stale')
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    s.status('V5', 'v5.axis.placement.status', 'stale')
    # UNCERTAIN: a real interruption of the real search
    s.scene('BASIC')
    ui.set_flag('search_budget', '0')
    flows.restart(ui)
    flows.open_scene(ui)
    ui.tap('v4.add.0.search')
    time.sleep(2)
    s.status('V4', 'v4.axis.search.status', 'uncertain')
    ui.tap('v4.detail')
    ui.wait('v5.screen')
    s.status('V5', 'v5.axis.search.status', 'uncertain')
    ui.clear_flag('search_budget')
    s.shot('v5-uncertain')
    # V7
    s.scene('BASIC', 'CE13FUTURE', ready='v7.screen')
    s.status('V7', 'v7.future', 'stale')
    s.shot('v7')
    return s.samples, gl


def probe_instance(ui, index):
    """Framebuffer value at the center of the top face, which is drawn at full palette color."""
    row = ui.dump_state()['render']['instances'][index]
    x, y = int(row['screenX']), int(row['screenY'])
    ui.dump_state('probe', f'{x},{y}')
    time.sleep(1)
    render = ui.dump_state()['render']
    return {'role': render['instances'][index]['role'], 'at': [x, y], 'rgba': render['probes'][0]['rgba']}


def probe_role(ui, role):
    rows = ui.dump_state()['render']['instances']
    return probe_instance(ui, next(i for i, row in enumerate(rows) if row['role'] == role))


def tokens_hash():
    digest = hashlib.sha256()
    for path in sorted((ROOT / 'packages/design-tokens').rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts:
            digest.update(path.read_bytes())
    return digest.hexdigest()


def run(case, ui):
    before = tokens_hash()
    base_samples, base_gl = collect(ui, '')
    flag = ','.join(f'{k}={v}' for k, v in {**OVERRIDE, **OVERRIDE_SIZES}.items())
    over_samples, over_gl = collect(ui, flag)
    flows.fresh_start(ui)
    after = tokens_hash()
    case.equal('제품 토큰 원본 불변', after, before)
    product = product_tokens()
    case.input(product_tokens=product, override=flag)
    for sample in base_samples:
        if 'pixel' in sample:
            case.equal(f"제품값 {sample['screen']} {sample['tag']} {sample['token']}", sample['pixel'], product[sample['token']])
    for sample in over_samples:
        if 'pixel' in sample:
            case.equal(f"변경값 {sample['screen']} {sample['tag']} {sample['token']}", sample['pixel'], OVERRIDE[sample['token']])
    for role, token in (('item', 'item'), ('selected', 'selected'), ('conflict', 'conflict'), ('unverified', 'unverified')):
        case.equal(f'GL 제품값 {role}', base_gl[role]['rgba'], product[token])
        case.equal(f'GL 변경값 {role}', over_gl[role]['rgba'], OVERRIDE[token])
    def height(samples, component):
        return [s['heightPx'] for s in samples if s['component'] == component and 'heightPx' in s]
    case.check('spacingLarge 변경이 버튼 높이를 바꿈', min(height(over_samples, 'CargoButton')) > max(height(base_samples, 'CargoButton')), 'larger', (height(base_samples, 'CargoButton')[:3], height(over_samples, 'CargoButton')[:3]))
    case.check('typeBody 변경이 글자 높이를 바꿈', height(over_samples, 'typography')[0] > height(base_samples, 'typography')[0], 'larger', (height(base_samples, 'typography'), height(over_samples, 'typography')))
    capture = {
        'kind': 'real UI pixels, node bounds and GL framebuffer', 'tokens_hash_before': before, 'tokens_hash_after': after,
        'override': flag, 'product': {'samples': base_samples, 'gl': base_gl}, 'override_run': {'samples': over_samples, 'gl': over_gl},
    }
    (EVIDENCE / 'ds-runtime-capture.json').write_text(json.dumps(capture, ensure_ascii=False, indent=1))
