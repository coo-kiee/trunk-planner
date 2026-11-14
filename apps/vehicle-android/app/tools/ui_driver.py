"""Drives the installed APK the way a person does: adb input, uiautomator nodes, real screenshots, activity dump.

Only the product's own emulator-5570 on the product adb server is addressed (see vehicle-port/tools/emu_lib.py, reused
read-only). There is no UI state injection anywhere in this file.
"""
import json, os, re, struct, subprocess, sys, time, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'apps/vehicle-android/vehicle-port/tools'))
import emu_lib  # noqa: E402

EVIDENCE = ROOT / 'evidence/product/vehicle-ui'
CAPTURES = EVIDENCE / 'captures'
LOGS = EVIDENCE / 'logs'
emu_lib.LOGS = LOGS  # the shared helper's logs go to this owner's folder, never the platform's
SERIAL = emu_lib.SERIAL
adb = emu_lib.adb
BOUNDS = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')


class StepFailed(AssertionError):
    pass


class Node:
    def __init__(self, element):
        self.element = element
        self.tag = element.get('resource-id', '')
        self.own_text = element.get('text', '')
        m = BOUNDS.match(element.get('bounds', '[0,0][0,0]'))
        self.left, self.top, self.right, self.bottom = (int(x) for x in m.groups())
        self.enabled = element.get('enabled') == 'true'

    @property
    def text(self):
        return ' '.join(t for t in (e.get('text', '') for e in self.element.iter()) if t)

    @property
    def center(self):
        return (self.left + self.right) // 2, (self.top + self.bottom) // 2


class Ui:
    def __init__(self, package):
        self.package = package
        self.user = adb('shell', 'am', 'get-current-user').stdout.strip()
        self.component = f'{package}/ai.cargo.planner.MainActivity'
        self.display = None  # a virtual form factor display when set; the main display otherwise
        self.capture_slot = None  # that display's slot in the emulator's own screenshot command
        self.capture_size = None  # (width, height) a capture must have, when known
        self.log = []

    # ---- process ----
    def start(self):
        where = ['--display', str(self.display)] if self.display is not None else []
        out = adb('shell', 'am', 'start', '-W', *where, '-n', self.component).stdout
        self.log.append({'am_start': out.strip().splitlines()[-1:]})

    def force_stop(self):
        adb('shell', 'am', 'force-stop', self.package)

    def alive(self):
        return adb('shell', 'pidof', self.package).stdout.strip()

    def clear_data(self):
        out = adb('shell', 'pm', 'clear', '--user', self.user, self.package).stdout.strip()
        return out

    def input(self, *args):
        where = ['-d', str(self.display)] if self.display is not None else []
        return adb('shell', 'input', *where, *args)

    # ---- nodes ----
    def nodes(self):
        """A dump can fail while the window changes, so it is retried a bounded number of times."""
        for _ in range(6):
            where = ['--display-id', str(self.display)] if self.display is not None else []
            dump = adb('shell', 'uiautomator', 'dump', '--compressed', *where, '/data/local/tmp/cargo-ui.xml', timeout=60)
            cat = adb('shell', 'cat', '/data/local/tmp/cargo-ui.xml', timeout=30)
            if 'dumped' in dump.stdout and cat.stdout.strip().startswith('<?xml'):
                try:
                    root = ET.fromstring(cat.stdout)
                    return [Node(e) for e in root.iter('node')]
                except ET.ParseError:
                    pass
            time.sleep(1)
        raise StepFailed('uiautomator dump unavailable')

    def nodes_full(self):
        """The uncompressed tree, for other apps' windows such as the official console."""
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/cargo-ui-full.xml', timeout=60)
        text = adb('shell', 'cat', '/data/local/tmp/cargo-ui-full.xml', timeout=30).stdout
        return [Node(e) for e in ET.fromstring(text).iter('node')]

    def find(self, tag, nodes=None):
        for node in (nodes if nodes is not None else self.nodes()):
            if node.tag == tag:
                return node
        return None

    def has(self, tag):
        return self.find(tag) is not None

    def wait(self, tag, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(tag)
            if node is not None:
                return node
            time.sleep(0.5)
        raise StepFailed(f'node {tag} did not appear in {timeout}s; shown: {sorted(n.tag for n in self.nodes() if n.tag)[:40]}')

    def wait_scrolled(self, tag, timeout=30):
        """For nodes that may appear outside the scrolled area, where uiautomator does not list them."""
        deadline = time.time() + timeout
        while True:
            try:
                return self.scroll_to(tag, attempts=4)
            except StepFailed:
                if time.time() > deadline:
                    raise

    def wait_gone(self, tag, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.find(tag) is None:
                return True
            time.sleep(0.5)
        raise StepFailed(f'node {tag} still present after {timeout}s')

    def scroll_to(self, tag, attempts=16):
        """Scrolls the panel column until the node is fully on screen. Off-screen content is not assumed to exist."""
        for direction in ('down', 'up'):
            unchanged = 0
            for _ in range(attempts):
                nodes = self.nodes()
                node = self.find(tag, nodes)
                if node is not None and self.fully_visible(node, nodes):
                    return node
                before = [(n.tag, n.top) for n in nodes if n.tag]
                self.swipe_panel(direction, nodes)
                time.sleep(0.5)
                # A first gesture right after a keyboard closes can be dropped, so one still frame is not the end.
                unchanged = unchanged + 1 if before == [(n.tag, n.top) for n in self.nodes() if n.tag] else 0
                if unchanged >= 2:
                    break
        # Scrolling has ended both ways: a node clipped by the edge is as visible as it will ever be.
        node = self.find(tag)
        if node is not None and node.bottom > node.top:
            return node
        raise StepFailed(f'node {tag} not reachable by scrolling')

    def screen_area(self, nodes):
        screen = next((n for n in nodes if n.tag.endswith('.screen')), None)
        return (screen.top, screen.bottom) if screen else (self.viewport(nodes)[1], self.viewport(nodes)[3])

    def fully_visible(self, node, nodes):
        if node.bottom <= node.top:
            return False
        top, bottom = self.screen_area(nodes)
        if node.top < top:  # the fixed header sits above the scrolling screen
            return node.bottom <= top
        return node.bottom < bottom - 1 and node.top > top + 1 or node.tag.endswith(('.screen', '.gl'))

    def viewport(self, nodes):
        """Screen area of the app from its own nodes. The compressed dump leaves out the window's layout nodes."""
        tagged = [n for n in nodes if n.tag and n.bottom > n.top]
        return min(n.left for n in tagged), min(n.top for n in tagged), max(n.right for n in tagged), max(n.bottom for n in tagged)

    def swipe_panel(self, direction, nodes):
        l, t, r, b = self.viewport(nodes)
        top, bottom = self.screen_area(nodes)
        gl = self.find('v4.gl', nodes)
        # Beside the 3D view, so a scroll never becomes a camera gesture, and away from the window's edge handle.
        x = (gl.right + r) // 2 if gl is not None and gl.right < r - 60 else r - 120
        span = (bottom - top) // 2
        y_low, y_high = bottom - 12, bottom - 12 - span
        y_from, y_to = (y_low, y_high) if direction == 'down' else (y_high, y_low)
        # Slow enough to end without a fling, so a small node is not jumped over.
        self.input('swipe', str(x), str(y_from), str(x), str(y_to), '1100')

    def tap(self, tag, settle=0.5):
        node = self.scroll_to(tag)
        if not node.enabled:
            raise StepFailed(f'{tag} is disabled')
        x, y = node.center
        self.input('tap', str(x), str(y))
        time.sleep(settle)

    def tap_xy(self, x, y, settle=0.5):
        self.input('tap', str(x), str(y))
        time.sleep(settle)

    def text(self, tag):
        node = self.scroll_to(tag)
        return node.text

    def type_text(self, tag, value):
        """Real text entry through the focused field, ASCII only: `input text` cannot produce Korean."""
        self.tap(tag)
        self.input('text', value.replace(' ', '%s'))
        time.sleep(0.5)
        self.hide_keyboard()

    def ime_shown(self):
        return 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method').stdout

    def hide_keyboard(self):
        """The system keyboard opens a moment after focus. Back goes to it only while it is really showing."""
        deadline = time.time() + 1.5
        while time.time() < deadline and not self.ime_shown():
            time.sleep(0.2)
        for _ in range(5):
            if not self.ime_shown():
                return
            adb('shell', 'input', 'keyevent', '4')
            time.sleep(0.5)
        raise StepFailed('system keyboard stayed open')

    def keypad_enter(self, field_tag, digits):
        """Sets a field with the app's own number pad. Key bounds are read once; the pad does not move while typing."""
        self.tap(field_tag)
        self.hide_keyboard()
        self.tap('keypad.clear', settle=0.2)
        nodes = self.nodes()
        for digit in str(digits):
            x, y = self.find(f'keypad.{digit}', nodes).center
            self.input('tap', str(x), str(y))
            time.sleep(0.12)
        shown = self.find('keypad.value')
        if shown is None or shown.text != str(digits):
            raise StepFailed(f'{field_tag}: keypad shows {shown.text if shown else None}, wanted {digits}')

    # ---- observations ----
    def dump_state(self, *args):
        out = adb('shell', 'dumpsys', 'activity', self.component, *args, timeout=60).stdout
        for line in out.splitlines():
            if 'CARGO_DUMP ' in line:
                return json.loads(line.split('CARGO_DUMP ', 1)[1])
        raise StepFailed('activity dump has no CARGO_DUMP line')

    def capture(self, name):
        """Writes a real, new PNG or raises. An old file must never stand in for what is on screen now."""
        CAPTURES.mkdir(parents=True, exist_ok=True)
        path = CAPTURES / f'{name}.png'
        path.unlink(missing_ok=True)
        if self.display is not None:
            self._capture_virtual(path)
        else:
            data = subprocess.run(['adb', '-s', SERIAL, 'exec-out', 'screencap', '-p'], cwd=ROOT, env=emu_lib.ENV, capture_output=True, timeout=60).stdout
            path.write_bytes(data)
        self._verify_png(path)
        return f'captures/{name}.png'

    def _capture_virtual(self, path):
        """The emulator's screenshot takes its own display slot, which stays fixed while Android's display id grows."""
        if self.capture_slot is None:
            raise StepFailed('virtual display capture needs the slot reported by the bridge')
        before = set(CAPTURES.glob('Screenshot_*.png'))
        result = adb('emu', 'screenrecord', 'screenshot', str(CAPTURES), '--display', str(self.capture_slot), timeout=60)
        new = set(CAPTURES.glob('Screenshot_*.png')) - before
        if len(new) != 1:
            raise StepFailed(f'slot {self.capture_slot} produced {len(new)} new files: {result.stdout.strip()[-200:]} {result.stderr.strip()[-200:]}')
        new.pop().replace(path)

    def _verify_png(self, path):
        data = path.read_bytes() if path.exists() else b''
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            raise StepFailed(f'{path.name}: not a PNG ({len(data)} bytes)')
        size = struct.unpack('>II', data[16:24])
        if self.capture_size is not None and size != tuple(self.capture_size):
            raise StepFailed(f'{path.name}: {size} but the target is {self.capture_size}')

    def raw_screen(self):
        """Framebuffer bytes as displayed: (width, height, rgba)."""
        data = subprocess.run(['adb', '-s', SERIAL, 'exec-out', 'screencap'], cwd=ROOT, env=emu_lib.ENV, capture_output=True, timeout=60).stdout
        width, height, _ = struct.unpack('<III', data[:12])
        header = len(data) - width * height * 4
        return width, height, data[header:]

    def pixel(self, x, y, screen=None):
        width, height, rgba = screen or self.raw_screen()
        at = (y * width + x) * 4
        return '#%02X%02X%02X' % (rgba[at], rgba[at + 1], rgba[at + 2])

    def status_swatch(self, tag, screen=None, density=1.0):
        """Color actually painted in a CargoStatus square. spacingSmall padding + half of spacingMedium square, in dp."""
        node = self.scroll_to(tag)
        status = node
        inset = int(16 * density)
        return self.pixel(status.left + inset, (status.top + status.bottom) // 2, screen)

    def seed(self, name):
        """Runs a named seed through the real reducer, judge and Room inside the app process."""
        out = adb('shell', 'am', 'broadcast', '-f', '32', '-n', f'{self.package}/ai.cargo.planner.DemoSeedReceiver', '--es', 'seed', name, timeout=120).stdout
        if 'SEED_DONE' not in out:
            raise StepFailed(f'seed {name}: {out.strip()[-400:]}')
        return out.split('data="', 1)[1].rsplit('"', 1)[0]

    def app_shell(self, command, timeout=60):
        """Runs inside this app's own sandbox on the app's real user, so it sees the files the process uses."""
        home = f'/data/user/{self.user}/{self.package}'
        return adb('shell', f"run-as {self.package} --user {self.user} sh -c 'cd {home} && {command}'", timeout=timeout)

    def set_flag(self, name, value):
        out = self.app_shell(f'mkdir -p files/debug-flags && echo "{value}" > files/debug-flags/{name}')
        if out.returncode != 0:
            raise StepFailed(f'flag {name}: {out.stderr}')

    def clear_flag(self, name):
        self.app_shell(f'rm -f files/debug-flags/{name}')

    def assert_contains(self, tag, *needles):
        text = self.text(tag)
        for needle in needles:
            if needle not in text:
                raise StepFailed(f'{tag}: expected "{needle}" in "{text}"')
        return text

    def assert_absent(self, *tags):
        nodes = self.nodes()
        present = [t for t in tags if self.find(t, nodes) is not None]
        if present:
            raise StepFailed(f'nodes must be absent: {present}')


def now_iso():
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
