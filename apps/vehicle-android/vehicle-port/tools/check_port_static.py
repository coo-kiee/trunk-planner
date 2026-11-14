#!/usr/bin/env python3
"""Source and manifest rules that the type system cannot state. Importable so that tests can feed it counterexamples."""
import json, re, sys
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
SDK_PACKAGES = re.compile(r'\b(ai\.pleos|android\.car)\b')
FORBIDDEN = {
    'gear': re.compile(r'\b(getGear|Gear[A-Z]\w*|GearValue)\b'),
    'seat': re.compile(r'\b(getSeat|Seat[A-Z]\w*|SeatValue)\b'),
    'car_info': re.compile(r'\b(CarInfo|getCarInfo|getVin|Vin\b)'),
    'vehicle_setter': re.compile(r'\b(setDoor\w*|setCurrentDoor\w*)\b'),
    'navi': re.compile(r'\b(NaviHelper|ai\.pleos\.playground\.navi)\b'),
    'runtime_permission_request': re.compile(r'\brequestPermissions\b'),
    'room_save_path': re.compile(r'\bai\.cargo\.data\b'),
    'background_job': re.compile(r'\bWorkManager\b'),
}


def check_sources(files):
    """files: iterable of (relative path, text). Returns a list of violations."""
    problems = []
    for rel, text in files:
        code = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
        in_sdk = '/sdk/' in rel
        if SDK_PACKAGES.search(code) and not in_sdk:
            problems.append(f'{rel}: SDK type outside sdk/')
        if in_sdk and re.search(r'^(class|object|interface|fun|data class|sealed)\b', code, re.M):
            problems.append(f'{rel}: public declaration inside sdk/')
        if re.search(r'\bFake[A-Z]', code):
            problems.append(f'{rel}: fake in main')
        problems += [f'{rel}: forbidden {name}' for name, rule in FORBIDDEN.items() if rule.search(code)]
    return problems


def check_build(build, manifest):
    problems = []
    if 'implementation("ai.pleos.playground:Vehicle:2.3.2")' not in build:
        problems.append('Vehicle must be an implementation dependency pinned to 2.3.2')
    if re.search(r'api\("ai\.pleos', build) or 'NaviHelper' in build or 'project(":data")' in build:
        problems.append('forbidden dependency')
    if 'pleos.car.permission.CAR_DOORS' not in manifest:
        problems.append('CAR_DOORS missing')
    code = re.sub(r'<!--.*?-->', '', manifest, flags=re.S)
    if re.search(r'NAVI_ROUTE|CAR_SPEED|distractionOptimized', code):
        problems.append('forbidden manifest entry')
    return problems


def self_test():
    """The rules must reject known bad inputs, otherwise a clean result means nothing."""
    bad = {
        'a/api/X.kt': 'import ai.pleos.playground.vehicle.Vehicle',
        'a/core/Y.kt': 'val x = requestPermissions(arrayOf(), 1)',
        'a/sdk/Z.kt': 'class Public',
        'a/core/W.kt': 'val g = vehicle.getGear()',
        'a/core/V.kt': 'class FakeThing',
        'a/core/U.kt': 'import ai.cargo.data.CargoStore',
    }
    return all(check_sources([(k, v)]) for k, v in bad.items()) and not check_sources([('a/core/Ok.kt', '// requestPermissions is not called\nval a = 1')])


def main():
    sources = [(str(p.relative_to(MODULE)), p.read_text()) for p in (MODULE / 'src/main/java').rglob('*.kt')]
    problems = check_sources(sources) + check_build((MODULE / 'build.gradle.kts').read_text(), (MODULE / 'src/main/AndroidManifest.xml').read_text())
    result = {'files': len(sources), 'problems': problems, 'rules_reject_counterexamples': self_test()}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if not problems and result['rules_reject_counterexamples'] else 1


if __name__ == '__main__':
    sys.exit(main())
