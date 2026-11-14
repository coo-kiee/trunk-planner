#!/usr/bin/env python3
"""Grades stay apart: fixture = fake HTTP + fake storage; local_integration = real relay + isolated PostgreSQL + real Room on
the emulator. A real phone and the vehicle UI are not part of this check. Ports 3100/3101 and existing databases are not used."""
import glob, json, os, re, select, signal, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'vehicle-port/tools'))
from emu_lib import ENV, LOGS, ROOT, SERIAL, WORKER, EmulatorBlocked, LogUnavailable, OwnEmulator, adb, gradle, logcat, write_json  # noqa: E402

MODULE = ROOT / 'apps/vehicle-android/sync'
ANDROID = ROOT / 'apps/vehicle-android'
RELAY_DIR = ROOT / 'services/relay'
RELAY_PORT, PHONE_PORT = 23187, 23188
CLAIM_S, CREDENTIAL_S = 25, 60


def junit(pattern):
    tests = []
    for path in sorted(glob.glob(str(MODULE / pattern), recursive=True)):
        for case in ET.parse(path).getroot().iter('testcase'):
            failure = case.find('failure')
            tests.append({'class': case.get('classname').split('.')[-1], 'name': case.get('name'),
                          'status': 'failed' if failure is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                          'failure': (failure.text or '')[:600] if failure is not None else None})
    return tests


class LocalRelay:
    """Starts the relay owner's own isolated stack. It removes its PostgreSQL when it stops."""

    def __init__(self, config=None):
        self.config = config

    def __enter__(self):
        env = {**ENV, 'RELAY_PORT': str(RELAY_PORT), 'VEHICLE_RELAY_BASE_URL': f'http://10.0.2.2:{RELAY_PORT}', 'PHONE_RELAY_BASE_URL': f'http://10.0.2.2:{RELAY_PORT}',
               'PHONE_WEB_BASE_URL': f'http://10.0.2.2:{PHONE_PORT}/'}
        if self.config:
            env['RELAY_CONFIG_FILE'] = str(self.config)
        env.pop('JAVA_TOOL_OPTIONS', None)
        self.log = (LOGS / 'sync-local-stack.log').open('a')
        self.process = subprocess.Popen(['node', 'test/support/local-stack.ts'], cwd=RELAY_DIR, env=env, stdout=subprocess.PIPE, stderr=self.log, text=True)
        deadline = time.time() + 180
        while time.time() < deadline:
            ready, _, _ = select.select([self.process.stdout], [], [], 2)
            if ready:
                line = self.process.stdout.readline()
                if not line:
                    break
                self.log.write(line)
                self.log.flush()
                if 'local_stack_ready' in line:
                    self.info = json.loads(line)
                    return self
            if self.process.poll() is not None:
                break
        raise RuntimeError('local relay stack did not become ready; see logs/sync-local-stack.log')

    def __exit__(self, *_):
        self.process.send_signal(signal.SIGTERM)
        try:
            self.process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.log.close()


def connected(log_name, class_names, **arguments):
    """One instrumentation run. Its results are read at once because the next run replaces the output folder."""
    adb('logcat', '-c')
    prefix = '-Pandroid.testInstrumentationRunnerArguments.'
    args = {'relayUrl': f'http://10.0.2.2:{RELAY_PORT}', 'phoneUrl': f'http://10.0.2.2:{PHONE_PORT}/', 'class': ','.join(class_names), **arguments}
    run = gradle(ANDROID, ':sync:connectedDebugAndroidTest', *[f'{prefix}{k}={v}' for k, v in args.items()], log_name=log_name, timeout=1500)
    tests = junit('build/outputs/androidTest-results/connected/**/*.xml')
    stages = [json.loads(line) for line in logcat('SYNCIT').splitlines() if line.startswith('{')]
    ok = run['exit'] == 0 and bool(tests) and all(t['status'] == 'passed' for t in tests)
    return {'status': 'passed' if ok else 'failed', 'command': run, 'tests': tests, 'stage_observations': stages}


def short_config():
    """Test profile only: a claim limit shorter than the credential, on the relay's real expiry code."""
    source = json.loads((RELAY_DIR / 'config/relay-config-v1.json').read_text())
    short = {**source, 'relayConfigVersion': 9003, 'profile': 'test', 'credentialTtlSeconds': CREDENTIAL_S,
             'ttlOverrideSeconds': {'pairing': CLAIM_S, 'draft': 3600, 'ack': 3600}}
    path = LOGS / 'relay-config-short-credential.json'
    path.write_text(json.dumps(short))
    return path


def reproduce_before_fix():
    """Records the counterexample on the code as it stands. Used once, before the fix, and kept as evidence."""
    fixture_run = gradle(ANDROID, ':sync:clean', ':sync:testDebugUnitTest', '--continue', log_name='f01-before-fixture.log')
    fixture = junit('build/test-results/testDebugUnitTest/*.xml')
    with LocalRelay(), OwnEmulator():
        real = connected('f01-before-real.log', ['ai.cargo.sync.PairingExpiryRealTest'])
    write_json('F-01-before.json', {'schema': 'vehicle-platform evidence v1', **WORKER, 'case': 'F-01 수정 전 재현', 'grade': 'local_integration(emulator)', 'real_relay': True, 'real_phone': 'not_tested',
                                    'real': {**real, 'expected': 'failed'}, 'fixture_failed': [t['name'] for t in fixture if t['status'] != 'passed'], 'fixture_exit': fixture_run['exit'],
                                    'boundary': '폰 쪽은 프로토콜 입력용 HTTP. 원 반례 전체 재실행은 e2e-verify 소유.'})
    print(json.dumps({'real': real['status'], 'failure': [t.get('failure', '')[:400] for t in real['tests'] if t['status'] != 'passed'], 'fixture_failed': [t['name'] for t in fixture if t['status'] != 'passed']}, ensure_ascii=False))
    return 0


def main():
    if '--reproduce-before-fix' in sys.argv:
        return reproduce_before_fix()
    started = time.time()
    LOGS.mkdir(parents=True, exist_ok=True)
    fixture_run = gradle(ANDROID, ':sync:clean', ':sync:testDebugUnitTest', log_name='sync-fixture-gradle.log')
    fixture = junit('build/test-results/testDebugUnitTest/*.xml')
    integration = {'status': 'not_run', 'reason': None}
    try:
        with OwnEmulator():
            with LocalRelay() as relay:
                production = connected('sync-connected-gradle.log', ['ai.cargo.sync.RelayRecreateTest', 'ai.cargo.sync.PairingExpiryRealTest'])
            with LocalRelay(short_config()):
                short = connected('sync-connected-short-gradle.log', ['ai.cargo.sync.ShortCredentialRealTest'], claimLimitSeconds=CLAIM_S, credentialSeconds=CREDENTIAL_S)
            tests = production['tests'] + short['tests']
            integration = {'status': 'passed' if production['status'] == 'passed' and short['status'] == 'passed' else 'failed', 'grade': 'local_integration',
                           'command': [production['command'], short['command']], 'tests': tests, 'stage_observations': production['stage_observations'] + short['stage_observations'],
                           'relay': {k: relay.info.get(k) for k in ('grade', 'profile', 'demoLocal', 'relayPort')},
                           'short_credential_profile': {'claim_seconds': CLAIM_S, 'credential_seconds': CREDENTIAL_S, 'note': '릴레이 test profile의 실제 만료 코드, 실제 시계'}}
    except (LogUnavailable, subprocess.TimeoutExpired) as error:
        integration = {'status': 'failed', 'reason': str(error)}
    except (RuntimeError, EmulatorBlocked) as error:
        integration = {'status': 'not_run', 'reason': str(error)}
    leaked = re.search(r'(vh_|ph_|qp_)[A-Za-z0-9_-]{12,}', json.dumps(integration))
    if leaked:
        integration = {'status': 'failed', 'reason': 'token shape found in collected output'}
    fixture_ok = fixture_run['exit'] == 0 and bool(fixture) and all(t['status'] == 'passed' for t in fixture)
    common = {'schema': 'vehicle-platform evidence v1', **WORKER, 'argv': ['bash', 'apps/vehicle-android/sync/tools/run-sync-tests.sh'], 'cwd': 'project', 'serial': SERIAL, 'real_phone': 'not_tested', 'real_vehicle': 'not_tested',
              'boundary': 'client 경계 시험. 사용자 선택 UI·차량 단독 시나리오·폰 대기 표시는 vehicle-ui/phone-web/e2e 소유이며 여기서 주장하지 않는다.'}
    write_json('sync.json', {**common, 'passed': fixture_ok and integration['status'] == 'passed', 'seconds': round(time.time() - started),
                             'fixture': {'grade': 'fixture', 'command': fixture_run, 'count': len(fixture), 'tests': fixture}, 'local_integration': integration})
    def named(prefix):
        return [t for t in fixture if t['name'].startswith(prefix)]
    def case(cid, resp, prefix, integ):
        tests = named(prefix)
        write_json(f'{cid}.json', {**common, 'case': cid, 'responsibility': resp, 'fixture': {'grade': 'fixture', 'tests': tests, 'passed': bool(tests) and all(t['status'] == 'passed' for t in tests)},
                                   'local_integration': {'grade': 'local_integration', 'status': integration['status'], 'covers': integ, 'test': 'RelayRecreateTest.real_room_keystore_process_death_and_relay'},
                                   'passed': bool(tests) and all(t['status'] == 'passed' for t in tests) and integration['status'] == 'passed'})
    case('CE-18-02', 'inbound 멱등·폐기/수락 ack client와 Room 명령 경계', 'ce18_', '재수신 멱등, crash 수렴, 중복 재제출, 해제 뒤 폰 거부, credential 복원')
    case('CE-19-02', 'typed conflict·pending·vehicleAcceptedRevision 및 명시 수락 API', 'ce19_', '실제 양쪽 수정 충돌→폰에 CONFLICT_REPORTED→기존유지 ack')
    case('F-01', '서버 claim·자격 만료 권위와 오프라인·실제 만료 구분', 'f01_', '실제 300초 경과 뒤 수신·수락, 짧은 자격의 실제 만료, 만료 전 오프라인에서 미전송 ack 보존')
    case('CE-20-02', 'network unavailable typed state·전경 retry·Room 비의존', 'ce20_', '닫힌 포트 실제 NetworkUnavailable, 미전달 ack 재시작 후 재전송')
    print(json.dumps({'fixture': f"{sum(t['status'] == 'passed' for t in fixture)}/{len(fixture)}", 'integration': integration['status'], 'reason': integration.get('reason')}, ensure_ascii=False))
    for t in fixture + integration.get('tests', []):
        if t['status'] != 'passed':
            print('FAILED', t['class'], t['name'], (t.get('failure') or '')[:900])
    return 0 if fixture_ok and integration['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
