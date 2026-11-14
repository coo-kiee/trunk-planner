#!/usr/bin/env bash
# Clean build of the independent harness, unit fixtures and real SDK behaviour on this product's own emulator.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
exec python3 "$ROOT/apps/vehicle-android/vehicle-port/tools/port_tests.py" "$@"
