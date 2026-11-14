#!/usr/bin/env bash
# Usage: run-scenario.sh --variant demoLocalDebug | --variant release --test-signing [--only CASE,CASE]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
exec python3 "$ROOT/apps/vehicle-android/app/tools/run_scenario.py" "$@"
