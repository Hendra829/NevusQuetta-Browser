#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then
  SDK="$(awk -F= '/^sdk.dir=/{sub(/^sdk.dir=/,""); print}' local.properties)"
fi
[[ -n "$SDK" && -d "$SDK" ]] || fail "Android SDK tidak ada. Set ANDROID_SDK_ROOT atau local.properties sdk.dir. Contoh: sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'"
[[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" || -x "$SDK/tools/bin/sdkmanager" ]] || true

command -v java >/dev/null || fail "JDK 17+ diperlukan."

[[ -f ./gradle/wrapper/gradle-wrapper.jar ]] || fail "gradle-wrapper.jar hilang."
BUILD_ROOT="${NEVUS_B_BUILD_ROOT:-/tmp/nq-b}"
rm -rf "$BUILD_ROOT"
cp -a "$ROOT" "$BUILD_ROOT"
printf 'sdk.dir=%s\n' "$SDK" > "$BUILD_ROOT/local.properties"
cd "$BUILD_ROOT"
bash ./gradlew --no-daemon assembleDebug
APK="$BUILD_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail "APK B tidak dihasilkan."
sha256sum "$APK" | tee "$APK.sha256"
printf 'B_ASSEMBLE=PASS %s\n' "$APK"
printf 'BUKAN ChromePublic.apk. Package com.nevus.quetta versi 1.2.2 lab WebView.\n'
