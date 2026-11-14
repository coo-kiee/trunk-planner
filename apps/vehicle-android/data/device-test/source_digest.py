"""Hash of exactly the sources this node's results depend on, so evidence can prove it matches the code."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
ANDROID = ROOT / 'apps/vehicle-android'


def source_files():
    files = list((ROOT / 'packages/contracts/kotlin').rglob('*.kt'))
    for module in ('core-geometry', 'core-scene', 'data'):
        base = ANDROID / module
        files += [base / 'build.gradle.kts', base / 'gradle.lockfile']
        files += [path for path in (base / 'src').rglob('*') if path.is_file()]
    schemas = ANDROID / 'data/schemas'
    files += [path for path in schemas.rglob('*') if path.is_file()]
    return sorted(files)


def manifest():
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_files()}


def digest():
    return hashlib.sha256('\n'.join(f'{name} {sha}' for name, sha in manifest().items()).encode()).hexdigest()
