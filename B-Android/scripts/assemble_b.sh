#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() { printf "ERROR: %s\n" "$*" >&2; exit 1; }

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then
  SDK="$(awk -F= '/^sdk.dir=/{sub(/^sdk.dir=/,""); print}' local.properties)"
fi
[[ -n "$SDK" && -d "$SDK" ]] || fail "Android SDK tidak ada. Set ANDROID_SDK_ROOT atau local.properties sdk.dir."
command -v java >/dev/null || fail "JDK 17+ diperlukan."
[[ -f ./gradle/wrapper/gradle-wrapper.jar ]] || fail "gradle-wrapper.jar hilang."

BUILD_ROOT="${NEVUS_B_BUILD_ROOT:-/tmp/nq-v09ab}"
rm -rf "$BUILD_ROOT"
cp -a "$ROOT" "$BUILD_ROOT"
printf "sdk.dir=%s\n" "$SDK" > "$BUILD_ROOT/local.properties"
cd "$BUILD_ROOT"

bash ./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
APK="$BUILD_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail "APK V0.9AB tidak dihasilkan."
sha256sum "$APK" | tee "$APK.sha256"
printf "V09AB_ASSEMBLE=PASS %s\n" "$APK"
printf "PACKAGE=com.nevus.quetta VERSION=0.9.0-ab\n"
