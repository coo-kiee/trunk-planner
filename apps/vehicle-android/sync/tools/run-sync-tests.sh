#!/usr/bin/env bash
# Fixture tests, then real Room/Keystore/process death against the relay on its own isolated PostgreSQL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"
exec python3 "$ROOT/apps/vehicle-android/sync/tools/sync_tests.py" "$@"
