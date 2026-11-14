#!/usr/bin/env python3
"""Runs the two official build commands and records exactly what they produced. Usage: record_builds.py [demo] [release]"""
import hashlib, json, subprocess, sys, time, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = ROOT / 'evidence/product/vehicle-ui'
sys.path.insert(0, str(Path(__file__).parent))
from run_scenario import VARIANTS, apk_info  # noqa: E402

JOBS = {
    'demo': ('build.json', ['bash', 'tools/product-env/gradle.sh', ':app:assembleDemoLocalDebug'], 'demoLocalDebug'),
    'unit': ('unit-tests.json', ['bash', 'tools/product-env/gradle.sh', ':render-gl:testDebugUnitTest', ':feature-library:testDebugUnitTest', ':feature-profile:testDebugUnitTest', ':feature-editor:testDebugUnitTest', ':feature-pairing:testDebugUnitTest', ':app:testDemoLocalDebugUnitTest'], None),
    'release': ('release-build.json', ['bash', 'tools/product-env/gradle.sh', ':app:clean', ':app:assembleRelease', '-PuseTestSigning=true'], 'release'),
}


def entries_digest(apk):
    """Over every zip entry. Two clean release builds were observed to differ only in the signing block outside the
    entries, so this shows what is reproducible, while the file hash stays the identity of the tested bytes."""
    digest = hashlib.sha256()
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            digest.update(name.encode())
            digest.update(archive.read(name))
    return digest.hexdigest()


def record(name):
    target, argv, variant = JOBS[name]
    started = time.time()
    log = EVIDENCE / 'logs' / target.replace('.json', '.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as out:
        code = subprocess.run(argv + ['--console=plain'], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL).returncode
    if variant is None:
        result = {'argv': argv, 'cwd': '.', 'exit': code, 'seconds': round(time.time() - started, 1), 'log': str(log.relative_to(EVIDENCE)), 'status': 'pass' if code == 0 else 'fail', 'grade': 'unit (JVM)'}
        (EVIDENCE / target).write_text(json.dumps(result, ensure_ascii=False, indent=1))
        print(target, result['status'])
        return code
    apk = VARIANTS[variant][1]
    result = {'argv': argv, 'cwd': '.', 'exit': code, 'seconds': round(time.time() - started, 1), 'log': str(log.relative_to(EVIDENCE)), 'variant': variant, 'status': 'pass' if code == 0 and apk.exists() else 'fail'}
    if apk.exists():
        result.update(apk=str(apk.relative_to(ROOT)), apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), apk_bytes=apk.stat().st_size, entries_sha256=entries_digest(apk), manifest=apk_info(apk))
    if variant == 'release':
        info = result.get('manifest', {})
        result['signing'] = 'test signing only: key outside the repository, not a distribution key'
        result['release_rules'] = {
            'cleartext_off': (info.get('usesCleartextTraffic') or '').endswith('=false'),
            'no_network_exception': info.get('networkSecurityConfig') is None,
            'no_seed_receiver': info.get('seedReceiverPresent') is False,
        }
        if not all(result['release_rules'].values()):
            result['status'] = 'fail'
    (EVIDENCE / target).write_text(json.dumps(result, ensure_ascii=False, indent=1))
    print(target, result['status'], result.get('apk_sha256'), result.get('release_rules', ''))
    return code


if __name__ == '__main__':
    sys.exit(max(record(n) for n in (sys.argv[1:] or ['unit', 'release', 'demo'])))
