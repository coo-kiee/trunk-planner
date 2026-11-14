"""Own-AVD helpers shared by the platform checks. Only emulator-5574 on the product adb server (5237) is ever addressed.

These checks have their own AVD because e2e-verify drives product_foundation (emulator-5570) and an overlap there
disturbed both runs. An instance on these ports is reused only if it is this AVD. Anything else stops the run untouched.
Other emulators, Chrome profiles and adb servers on this machine are never touched. `wm size`, user-rotation and
hmg_car_service are not used.
"""
import hashlib, json, os, shutil, socket, subprocess, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / 'evidence/product/vehicle-platform'
LOGS = EVIDENCE / 'logs'
SERIAL, AVD, CONSOLE, GRPC = 'emulator-5574', 'platform_checks', 5574, 8756
CONSOLE_FORWARD = 18288
MIN_FREE_GIB = 5
WORKER = {
    'worker': 'claude-1', 'provider': 'claude', 'observed_model': 'claude-opus-5-5', 'phase': 'execute', 'attempt': 7,
    'team': '12e1058e-1d29-4b52-a24b-c4d94aa858b1', 'native_session': None,
    'usage': {'input': None, 'output': None, 'cache': None, 'reasoning': None, 'provenance': 'controller accounting; unknown is not zero'},
}


def load_env():
    out = subprocess.run(['bash', '-c', 'source tools/product-env/env.sh && env -0'], cwd=ROOT, capture_output=True, check=True).stdout
    return dict(item.split('=', 1) for item in out.decode().split('\0') if '=' in item)


ENV = {**load_env(), 'ANDROID_SERIAL': SERIAL}


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(argv, cwd=ROOT, timeout=900, env=None):
    return subprocess.run(argv, cwd=cwd, env=env or ENV, capture_output=True, text=True, timeout=timeout)


def gradle(project_dir, *tasks, log_name, timeout=900):
    """Runs the shared wrapper under the isolated env and keeps the whole log as evidence."""
    argv = [str(ROOT / 'apps/vehicle-android/gradlew'), '-p', str(project_dir), '--console=plain', *tasks]
    result = run(argv, timeout=timeout)
    LOGS.mkdir(parents=True, exist_ok=True)
    (LOGS / log_name).write_text(result.stdout + result.stderr)
    return {'argv': argv[1:], 'cwd': str(ROOT.name), 'exit': result.returncode, 'log': f'logs/{log_name}'}


def adb(*args, timeout=60):
    return subprocess.run(['adb', '-s', SERIAL, *args], cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=timeout)


class LogUnavailable(RuntimeError):
    pass


def logcat(tag, attempts=3, timeout=60):
    """Dumps one tag. A dump can stall right after instrumentation or under host load, so it is retried with a bound.
    Raises LogUnavailable when no dump arrived: missing evidence must never read as an empty, passing log."""
    for _ in range(attempts):
        try:
            result = adb('logcat', '-d', '-v', 'raw', '-s', f'{tag}:I', timeout=timeout)
        except subprocess.TimeoutExpired:
            time.sleep(5)
            continue
        if result.returncode == 0:
            return result.stdout
        time.sleep(5)
    raise LogUnavailable(f'logcat for {tag} unavailable after {attempts} attempts')


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(('127.0.0.1', port)) == 0


class EmulatorBlocked(RuntimeError):
    pass


AVD_SETTINGS = {'image.sysdir.1': 'system-images/android-34/pleos_car_emulator/arm64-v8a/', 'disk.dataPartition.size': '3G', 'hw.keyboard': 'yes',
                'hw.lcd.width': '2560', 'hw.lcd.height': '1440', 'hw.lcd.density': '160', 'hw.mainKeys': 'no'}


def ensure_avd():
    """Creates this check's AVD once, from the same isolated image and display settings as the foundation AVD."""
    directory = Path(ENV['ANDROID_AVD_HOME']) / f'{AVD}.avd'
    config = directory / 'config.ini'
    if not config.exists():
        result = subprocess.run(['avdmanager', 'create', 'avd', '-n', AVD, '-k', 'system-images;android-34;android-automotive;arm64-v8a', '-p', str(directory)],
                                input='no\n', cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=180)
        if not config.exists():
            raise EmulatorBlocked(f'AVD creation failed: {result.stderr[-300:]}')
        lines = [line for line in config.read_text().splitlines() if line.split('=')[0] not in AVD_SETTINGS]
        config.write_text('\n'.join(lines + [f'{k}={v}' for k, v in AVD_SETTINGS.items()]) + '\n')


class OwnEmulator:
    """Boots the AVD if nothing is there; stops it on exit only when this object started it."""

    def __enter__(self):
        free = shutil.disk_usage(ROOT).free
        if free < MIN_FREE_GIB * 1024 ** 3:
            raise EmulatorBlocked(f'free disk {free} below {MIN_FREE_GIB}GiB')
        self.process = None
        LOGS.mkdir(parents=True, exist_ok=True)
        subprocess.run(['adb', 'start-server'], cwd=ROOT, env=ENV, capture_output=True, timeout=60)
        if port_open(CONSOLE):
            name = adb('emu', 'avd', 'name').stdout.split('\n')[0].strip()
            if name != AVD:
                raise EmulatorBlocked(f'port {CONSOLE} belongs to {name!r}')
        elif port_open(GRPC):
            raise EmulatorBlocked(f'gRPC port {GRPC} belongs to another owner')
        else:
            ensure_avd()
            log = (LOGS / 'emulator.log').open('ab')
            self.process = subprocess.Popen(
                ['emulator', '-avd', AVD, '-port', str(CONSOLE), '-grpc', str(GRPC), '-grpc-use-token', '-no-snapshot', '-no-boot-anim',
                 '-no-audio', '-gpu', 'host', '-memory', '4096', '-no-window'],
                cwd=ROOT, env=ENV, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + 300
        while time.time() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise EmulatorBlocked('own emulator exited during boot; see logs/emulator.log')
            if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1':
                time.sleep(5)
                return self
            time.sleep(3)
        raise EmulatorBlocked('own emulator boot timeout')

    def __exit__(self, *_):
        if self.process is not None and self.process.poll() is None:
            adb('emu', 'kill')
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=30)


class VehicleConsole:
    """The image's console page must stay open for its heartbeat. Same HTTP calls that page sends, own Chrome profile."""
    CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

    def __enter__(self):
        adb('forward', f'tcp:{CONSOLE_FORWARD}', 'tcp:8088')
        self.profile = tempfile.mkdtemp(prefix='chrome-', dir=ENV['PRODUCT_ENV'])
        self.base = f'http://127.0.0.1:{CONSOLE_FORWARD}'
        self.chrome = subprocess.Popen([self.CHROME, '--headless=new', '--disable-gpu', '--no-first-run', f'--user-data-dir={self.profile}',
                                        '--remote-debugging-port=0', self.base + '/'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(8)
        return self

    def put(self, path, ready_timeout=60):
        """The console answers 503 until its page heartbeat is up, so a bounded retry waits for a real answer."""
        deadline = time.time() + ready_timeout
        while True:
            code = subprocess.run(['curl', '-sS', '-m', '10', '-X', 'PUT', '-o', '/dev/null', '-w', '%{http_code}', self.base + path],
                                  capture_output=True, text=True, timeout=20).stdout
            if code != '503' or time.time() >= deadline:
                return code
            time.sleep(1)

    def __exit__(self, *_):
        self.chrome.terminate()
        try:
            self.chrome.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.chrome.kill()
        shutil.rmtree(self.profile, ignore_errors=True)
        adb('forward', '--remove', f'tcp:{CONSOLE_FORWARD}')


def write_json(name, value):
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
