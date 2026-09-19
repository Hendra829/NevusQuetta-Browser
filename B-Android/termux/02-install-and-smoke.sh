#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

project_root="$HOME/NevusQuetta-Browser"
apk="$project_root/B-Android/app/build/outputs/apk/debug/app-debug.apk"
package_name='com.nevus.quetta'

test -s "$apk"
sha256sum "$apk"

if ! command -v adb >/dev/null 2>&1; then
  pkg install -y android-tools
fi

adb devices
adb install -r "$apk"
adb shell am force-stop "$package_name"
adb shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1
sleep 5

if adb logcat -d -t 500 | grep -E 'FATAL EXCEPTION|ANR in com\.nevus\.quetta'; then
  echo 'RUNTIME_GATE=FAIL' >&2
  exit 1
fi

echo 'RUNTIME_SMOKE=PASS'

