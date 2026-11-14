#!/usr/bin/env bash
# Tools are read-only references; all mutable state belongs to this product.
PRODUCT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export PRODUCT_ENV="$PRODUCT_ROOT/tools/product-env/state"
export JAVA_HOME="${PRODUCT_JAVA_HOME:-$PRODUCT_ROOT/tools/sdk-probe/env/jdk/Contents/Home}"
export ANDROID_HOME="${PRODUCT_ANDROID_HOME:-$PRODUCT_ROOT/tools/sdk-probe/env/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$PRODUCT_ENV/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export GRADLE_USER_HOME="${PRODUCT_GRADLE_USER_HOME:-$PRODUCT_ENV/gradle-home}"
export ANDROID_ADB_SERVER_PORT=5237
export ADB_SERVER_SOCKET=tcp:localhost:5237
export ANDROID_SERIAL=emulator-5570
export ADB_VENDOR_KEYS="$ANDROID_USER_HOME/adbkey"
export GRADLE_OPTS="-Duser.home=$PRODUCT_ENV/jvm-home -Dandroid.builder.sdkDownload=false"
export JAVA_TOOL_OPTIONS="-Duser.home=$PRODUCT_ENV/jvm-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$PRODUCT_ENV/jvm-home" "$GRADLE_USER_HOME"
