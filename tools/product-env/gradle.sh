#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/env.sh"
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "JDK17 missing: PRODUCT_JAVA_HOME" >&2; exit 66; }
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || { echo "Android35 missing: PRODUCT_ANDROID_HOME; automatic install disabled" >&2; exit 66; }
exec "$PRODUCT_ROOT/apps/vehicle-android/gradlew" -p "$PRODUCT_ROOT/apps/vehicle-android" "$@"
