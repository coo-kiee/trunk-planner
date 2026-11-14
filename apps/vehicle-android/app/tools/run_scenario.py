#!/usr/bin/env python3
"""Installs the exact built APK on this product's own emulator, proves its hash, then runs the real scenarios.

Exit status is nonzero when any requested case fails. Result grades stay separate per variant: release results never
come from the demo APK and demo relay connection results are never counted as release results.
"""
import argparse, hashlib, json, shlex, subprocess, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ui_driver import EVIDENCE, LOGS, ROOT, SERIAL, Ui, adb, emu_lib, now_iso  # noqa: E402
from evidence_lib import Runner  # noqa: E402
import ce_checks as ce  # noqa: E402
import core_flow, ds_capture, render_checks  # noqa: E402

MODULE = ROOT / 'apps/vehicle-android/app'
VARIANTS = {
    'demoLocalDebug': ('ai.cargo.planner.demolocal', MODULE / 'build/outputs/apk/demoLocalDebug/app-demoLocalDebug.apk'),
    'release': ('ai.cargo.planner', MODULE / 'build/outputs/apk/release/app-release.apk'),
}


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def apk_info(apk):
    build_tools = sorted((Path(emu_lib.ENV['ANDROID_HOME']) / 'build-tools').iterdir())[-1]
    aapt = str(build_tools / 'aapt2')
    badging = subprocess.run([aapt, 'dump', 'badging', str(apk)], capture_output=True, text=True).stdout
    manifest = subprocess.run([aapt, 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], capture_output=True, text=True).stdout
    apksigner = subprocess.run([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], capture_output=True, text=True, env=emu_lib.ENV)
    return {
        'package': badging.split("name='", 1)[1].split("'", 1)[0] if "name='" in badging else None,
        'usesCleartextTraffic': next((l.strip() for l in manifest.splitlines() if 'usesCleartextTraffic' in l), None),
        'networkSecurityConfig': next((l.strip() for l in manifest.splitlines() if 'networkSecurityConfig' in l), None),
        'seedReceiverPresent': 'DemoSeedReceiver' in manifest,
        'debuggable': next((l.strip() for l in manifest.splitlines() if 'debuggable' in l), None),
        'signer': next((l for l in apksigner.stdout.splitlines() if 'certificate DN' in l), None),
    }


def install_and_prove(ui, apk):
    host = sha256(apk)
    result = adb('install', '-r', str(apk), timeout=300)
    if 'Success' not in result.stdout:
        raise SystemExit(f'install failed: {result.stdout} {result.stderr}')
    path = adb('shell', 'pm', 'path', '--user', ui.user, ui.package).stdout.strip().split('package:')[-1]
    device = adb('shell', 'sha256sum', path).stdout.split(' ')[0]
    if device != host:
        raise SystemExit(f'installed APK differs: host {host} device {device}')
    return {'apk': str(Path(apk).relative_to(ROOT)), 'apk_sha256': host, 'installed_sha256': device, 'installed_matches': True, 'device_path_kind': 'pm path'}


def settle_after_boot(ui, steady=24, limit=240):
    """After a cold boot the vehicle's navigation app can take the front up to a minute later, over a test already under way.
    Cases start only once this app has stayed in front for `steady` seconds. Nothing here is a case result."""
    started = time.time()
    restarts = 0
    ui.start()
    shown_since = None
    while time.time() - started < limit:
        if ui.has('v0.screen') or any(n.tag.startswith('v') and '.' in n.tag for n in ui.nodes()):
            shown_since = shown_since or time.time()
            if time.time() - shown_since >= steady:
                return {'seconds': round(time.time() - started, 1), 'restarts': restarts, 'steady_seconds': steady}
        else:
            shown_since = None
            restarts += 1
            ui.start()
        time.sleep(3)
    raise SystemExit(f'app did not stay in front for {steady}s within {limit}s after boot ({restarts} restarts)')


def write_scenario(name, variant, argv, context, results):
    failed = sorted(k for k, v in results.items() if v != 'pass')
    summary = {
        'variant': variant, 'argv': argv, 'cwd': '.', 'finished': now_iso(), **context, 'cases': results,
        'status': 'pass' if results and not failed else 'fail', 'failed': failed,
        'real_vehicle': 'not_tested', 'real_phone': 'not_tested',
    }
    (EVIDENCE / name).write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    return summary


def merge_rerun(variant, context, results):
    """A partial rerun updates the summary only for the very same APK bytes, and says that it did."""
    path = EVIDENCE / ('release-scenario.json' if variant == 'release' else 'scenario.json')
    if not path.exists():
        return
    summary = json.loads(path.read_text())
    if summary.get('apk_sha256') != context['apk_sha256']:
        return
    summary['cases'].update(results)
    summary.setdefault('reruns', []).append({'at': now_iso(), 'cases': results})
    summary['failed'] = sorted(k for k, v in summary['cases'].items() if v != 'pass')
    summary['status'] = 'pass' if not summary['failed'] else 'fail'
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=1))


def disabled_pairing(case, ui):
    import flows
    flows.fresh_start(ui)
    ui.tap('v0.pairing')
    ui.wait('v3.screen')
    case.contains('공인 HTTPS 미설정 비활성 안내', ui.text('v3.status'), '공인 HTTPS 서버 미설정')
    case.contains('상태 코드 DISABLED', ui.text('v3.statusCode'), 'DISABLED_VEHICLE_URL_UNSET')
    case.check('QR 버튼 없음', not ui.has('v3.start'), 'absent', ui.has('v3.start'))
    case.record['captures'].append(ui.capture('release-pairing-disabled'))
    case.input(release_pairing='not_run: 공인 HTTPS·호스팅 미승인')


def common_cases(runner, ui, prefix):
    grade = ce.GRADE_UI
    runner.run('core-scenario', '제품1 등록·배치·확정·저장→kill→고정좌표 복원→제품2 추가→동시 판정', grade, lambda c: core_flow.core(c, ui, prefix + 'core'))
    runner.run('driving', '주행 제한: 편집·렌더 중단, 초안 보존, 정차 복원', grade + '+real_console', lambda c: core_flow.driving_restriction(c, ui, prefix + 'driving'))
    runner.run('recreate', '프로세스 재생성 후 초안·undo·redo·카메라', grade, lambda c: core_flow.recreate(c, ui, prefix + 'recreate'))


DEMO_CASES = {
    'CE-03-02': ('입구/내부 독립 표시', ce.GRADE_SEEDED, ce.ce03),
    'CE-04-02': ('입구 미측정 통과 금지', ce.GRADE_SEEDED, ce.ce04),
    'CE-05-02': ('입구 통과와 경로 미검증 독립', ce.GRADE_SEEDED, ce.ce05),
    'CE-06-02': ('탐색 중단은 불가가 아님', ce.GRADE_SEEDED + '+real_ui_input', ce.ce06),
    'S-SEARCH-05': ('정상 후보 소진은 불가 미증명', ce.GRADE_SEEDED, ce.search05),
    'CE-09-02': ('지지 부족·공중과 안전 미판정', ce.GRADE_SEEDED + '+real_ui_input', ce.ce09),
    'CE-10-02': ('item 새 버전과 scene 참조', ce.GRADE_SEEDED + '+real_ui_input', ce.ce10),
    'CE-11-02': ('stale 숨김과 재검증', ce.GRADE_SEEDED, ce.ce11),
    'CE-12-02': ('저장 중 강제 종료', ce.GRADE_SEEDED + '+real_kill', ce.ce12),
    'CE-13-02': ('checksum 손상·미래 schema', ce.GRADE_SEEDED + '+real_file_damage', ce.ce13),
    'CE-14-02': ('migration 실패 보존', ce.GRADE_SEEDED + '+real_migration', ce.ce14),
    'CE-15-02': ('재생성 복원·배경 렌더 중단', ce.GRADE_SEEDED + '+real_ui_input', ce.ce15),
    'CE-16-02': ('주행 제한 편집·QR 숨김', ce.GRADE_SEEDED + '+real_console', ce.ce16),
    'V5-driving': ('V5·V6 주행 중 동작 숨김과 정차 복원', ce.GRADE_SEEDED + '+real_console', ce.v5_driving),
    'input-bounds': ('입력 경계와 저장 거부 시 종료 없음', ce.GRADE_UI, ce.input_bounds),
    'CE-17-02': ('port 상태별 표시', 'emulator:real_sdk+fixture_state_on_real_screen', ce.ce17),
    'CE-21-02': ('좌우 방향과 문구 일치', ce.GRADE_SEEDED + '+real_ui_input', ce.ce21),
    'CE-22-03': ('근거 등급 표시', ce.GRADE_SEEDED, ce.ce22),
    'S-PROFILE': ('미지원·합성 profile 표시', ce.GRADE_UI + '+seeded_real_store', ce.profiles),
    'V6-loading': ('적재 순서와 문 제안', ce.GRADE_SEEDED + '+real_console', ce.loading),
    'render': ('깊이·picking·frame time·메모리·idle', ce.GRADE_SEEDED + '+real_gl', render_checks.measure),
    'ds-capture': ('실제 화면·GL 토큰 값과 시험 override 관측', ce.GRADE_SEEDED + '+real_pixels', ds_capture.run),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', choices=VARIANTS, required=True)
    parser.add_argument('--test-signing', action='store_true')
    parser.add_argument('--only', default='', help='comma list of cases; a partial run never replaces the summary file')
    args = parser.parse_args()
    if args.variant == 'release' and not args.test_signing:
        raise SystemExit('release runs here require --test-signing: there is no distribution signature')
    package, apk = VARIANTS[args.variant]
    if not Path(apk).exists():
        raise SystemExit(f'{apk} missing. Build it first; this runner never rebuilds what it tests.')
    LOGS.mkdir(parents=True, exist_ok=True)
    only = {x for x in args.only.split(',') if x}
    with emu_lib.OwnEmulator():
        ui = Ui(package)
        context = {'variant': args.variant, 'command': ' '.join(shlex.quote(a) for a in sys.argv[1:]), **install_and_prove(ui, apk), 'apk_manifest': apk_info(apk)}
        context['boot_settle'] = settle_after_boot(ui)
        prefix = 'release-' if args.variant == 'release' else ''
        runner = Runner(ui, context, prefix=prefix)
        if not only or 'common' in only:
            common_cases(runner, ui, prefix)
        if args.variant == 'release':
            if not only or 'input-bounds' in only:
                runner.run('input-bounds', '입력 경계와 저장 거부 시 종료 없음', ce.GRADE_UI, lambda c: ce.input_bounds(c, ui))
            if not only or 'pairing' in only:
                runner.run('pairing-disabled', 'release 페어링 명시 비활성·cleartext 금지', ce.GRADE_UI, lambda c: disabled_pairing(c, ui))
        else:
            import sync_checks
            cases = {**DEMO_CASES, **render_checks.CASES_F02, **sync_checks.CASES}
            for ident, (title, grade, body) in cases.items():
                if not only or ident in only:
                    runner.run(ident, title, grade, lambda c, b=body: b(c, ui))
        if not only:
            name = 'release-scenario.json' if args.variant == 'release' else 'scenario.json'
            summary = write_scenario(name, args.variant, sys.argv[1:], context, runner.results)
            print(json.dumps({k: summary[k] for k in ('status', 'failed', 'apk_sha256')}, ensure_ascii=False))
        else:
            merge_rerun(args.variant, context, runner.results)
        print(json.dumps(runner.results, ensure_ascii=False, indent=0))
        return 1 if any(v != 'pass' for v in runner.results.values()) else 0


if __name__ == '__main__':
    sys.exit(main())
