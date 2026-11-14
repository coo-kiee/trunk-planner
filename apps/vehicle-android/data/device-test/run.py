#!/usr/bin/env python3
"""Runs :data:connectedDebugAndroidTest on this product's own AVD and records what actually happened.

Only emulator-5570 on the product adb server (5237) is used. An instance this script did not start is reused only when
it is already this product's AVD; anything else on those ports stops the run with a checkpoint. Other AVDs, other adb
servers and unrelated processes are never touched. The result is grade=emulator: it is not a real device, vehicle or
power loss test.
"""
import hashlib, json, os, socket, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import source_digest  # noqa: E402

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / 'evidence/product/vehicle-core'
SERIAL, AVD, CONSOLE, GRPC = 'emulator-5570', 'product_foundation', 5570, 8754
TASK = ['bash', 'tools/product-env/gradle.sh', ':data:connectedDebugAndroidTest', '--console=plain']
RESULTS = ROOT / 'apps/vehicle-android/data/build/outputs/androidTest-results/connected'


def load_env():
    out = subprocess.run(['bash', '-c', 'source tools/product-env/env.sh && env -0'], cwd=ROOT, capture_output=True, check=True).stdout
    return dict(item.split('=', 1) for item in out.decode().split('\0') if '=' in item)


ENV = load_env()


def adb(*args, timeout=60, check=False):
    return subprocess.run(['adb', '-s', SERIAL, *args], cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=timeout, check=check)


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def checkpoint(message):
    (EVIDENCE / 'room-checkpoint.json').write_text(json.dumps({'status': 'blocked', 'cause': message, 'at': time.time()}, ensure_ascii=False, indent=2))
    print(message, file=sys.stderr)
    return 75


def avd_name():
    return adb('emu', 'avd', 'name').stdout.split('\n')[0].strip()


def boot():
    log = (EVIDENCE / 'room-emulator.log').open('wb')
    process = subprocess.Popen(
        ['emulator', '-avd', AVD, '-port', str(CONSOLE), '-grpc', str(GRPC), '-grpc-use-token', '-no-snapshot', '-no-boot-anim',
         '-no-audio', '-gpu', 'host', '-memory', '4096', '-no-window'],
        cwd=ROOT, env=ENV, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 300
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError('own emulator exited during boot; see room-emulator.log')
        if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1':
            time.sleep(5)
            return process
        time.sleep(3)
    raise RuntimeError('own emulator boot timeout')


def parse_junit():
    tests = []
    for path in sorted(RESULTS.rglob('TEST-*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            failure = case.find('failure')
            tests.append({'class': case.attrib.get('classname'), 'name': case.attrib['name'], 'seconds': float(case.attrib.get('time', 0)),
                          'status': 'failed' if failure is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                          'failure': (failure.text or '')[:800] if failure is not None else None})
    return tests


def main():
    started_here = None
    subprocess.run(['adb', 'start-server'], cwd=ROOT, env=ENV, capture_output=True, timeout=60)
    try:
        if port_open(CONSOLE):
            name = avd_name()
            if name != AVD:
                return checkpoint(f'port {CONSOLE} is in use by {name!r}, not this product AVD; left untouched')
        elif port_open(GRPC):
            return checkpoint(f'gRPC port {GRPC} is in use by another owner; left untouched')
        else:
            started_here = boot()
        device = {key: adb('shell', 'getprop', prop).stdout.strip() for key, prop in {
            'sdk': 'ro.build.version.sdk', 'fingerprint': 'ro.build.fingerprint', 'model': 'ro.product.model', 'characteristics': 'ro.build.characteristics'}.items()}
        device['serial'] = SERIAL
        device['avd'] = avd_name()
        device['android_user'] = adb('shell', 'am', 'get-current-user').stdout.strip()
        adb('logcat', '-c')
        begun = time.time()
        with (EVIDENCE / 'room-gradle.log').open('w') as log:
            gradle = subprocess.run(TASK, cwd=ROOT, env=ENV, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        receipts = [json.loads(line.split('CargoKillReceipt:', 1)[1].strip())
                    for line in adb('logcat', '-d', '-s', 'CargoKillReceipt:I', timeout=120).stdout.splitlines() if 'CargoKillReceipt:' in line]
        # Kept for diagnosis of any failure: only this test package's process lifecycle lines and crashes.
        lifecycle = adb('logcat', '-d', '-s', 'ActivityManager:I', 'AndroidRuntime:E', 'CargoKillReceipt:I', timeout=120).stdout
        (EVIDENCE / 'room-logcat.log').write_text('\n'.join(line for line in lifecycle.splitlines() if 'ai.cargo.data' in line or 'AndroidRuntime' in line or 'CargoKillReceipt' in line))
        tests = parse_junit()
        apks = sorted((ROOT / 'apps/vehicle-android/data/build/outputs/apk/androidTest/debug').glob('*.apk'))
        result = {
            'schema': 'vehicle-core room evidence v1', 'grade': 'emulator', 'argv': TASK, 'cwd': str(ROOT), 'exit': gradle.returncode,
            'seconds': round(time.time() - begun, 1), 'device': device, 'source_digest': source_digest.digest(),
            'runner': {'argv': ['python3', 'apps/vehicle-android/data/device-test/run.py'], 'started_own_emulator': started_here is not None},
            'test_apk': [{'name': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()} for apk in apks],
            'expected': 'all connected tests pass, receipts show real SIGKILL of a separate writer pid',
            'actual': {'total': len(tests), 'passed': sum(t['status'] == 'passed' for t in tests), 'failed': sum(t['status'] == 'failed' for t in tests)},
            'tests': tests, 'kill_receipts': receipts,
            'not_observed': {'real_device': 'not_run', 'real_vehicle': 'not_run', 'power_loss': 'not_run',
                             'note': 'SIGKILL proves process death only. SQLite durability under power loss was not exercised.'},
        }
        (EVIDENCE / 'room.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps(result['actual']), 'exit', gradle.returncode)
        return gradle.returncode
    except RuntimeError as error:
        return checkpoint(str(error))
    finally:
        if started_here is not None:
            subprocess.run(['adb', '-s', SERIAL, 'emu', 'kill'], cwd=ROOT, env=ENV, capture_output=True, timeout=60)
            try:
                started_here.wait(timeout=60)
            except subprocess.TimeoutExpired:
                started_here.terminate()
            subprocess.run(['adb', 'kill-server'], cwd=ROOT, env=ENV, capture_output=True, timeout=60)


if __name__ == '__main__':
    sys.exit(main())
