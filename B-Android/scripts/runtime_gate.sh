#!/usr/bin/env bash
set -Eeuo pipefail

API="${1:?API level wajib diisi}"
PKG="com.nevus.quetta"
TEST_PKG="com.nevus.quetta.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
OUT="runtime-api-${API}"
mkdir -p "$OUT"
RESULTS="$OUT/results.txt"
: > "$RESULTS"

pass() { echo "PASS: $*" | tee -a "$RESULTS"; }
fail() { echo "FAIL: $*" | tee -a "$RESULTS" >&2; exit 1; }
run_class() {
  local cls="$1"
  echo "=== instrumentation: $cls ===" | tee -a "$RESULTS"
  adb shell am instrument -w -r -e class "$cls" "$TEST_PKG/$RUNNER" | tee "$OUT/${cls##*.}.txt"
  grep -q "OK (" "$OUT/${cls##*.}.txt" || fail "instrumentation $cls"
  pass "instrumentation $cls"
}

echo "NevusQuetta runtime gate API $API" | tee -a "$RESULTS"
adb wait-for-device
BOOT="$(adb shell getprop sys.boot_completed | tr -d "\r")"
[[ "$BOOT" == "1" ]] || fail "emulator belum boot sempurna"
pass "emulator boot API $API"

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c

./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -s "$APP_APK" ]] || fail "debug APK hilang"
[[ -s "$TEST_APK" ]] || fail "androidTest APK hilang"
adb install -r -t "$APP_APK" | tee "$OUT/install-app.txt"
adb install -r -t "$TEST_APK" | tee "$OUT/install-test.txt"
pass "APK + androidTest installed"

run_class "com.nevus.quetta.runtime.RuntimeUiTest"
run_class "com.nevus.quetta.runtime.DownloadRuntimeTest"
run_class "com.nevus.quetta.runtime.BrowserDatabaseMigrationRuntimeTest"
run_class "com.nevus.quetta.runtime.V09CRuntimeTest"
run_class "com.nevus.quetta.runtime.PersistenceSeedTest"

adb shell am force-stop "$PKG"
sleep 1
pass "force-stop process death issued"
run_class "com.nevus.quetta.runtime.PersistenceVerifyTest"
pass "bookmark/history/tab state survived process death"

adb shell am start -W -n "$PKG/.MainActivity" -d "https://example.com/" | tee "$OUT/foreground-start.txt"
sleep 1
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/foreground-resume.txt"
sleep 1
adb shell am send-trim-memory "$PKG" RUNNING_LOW | tee "$OUT/trim-memory.txt"
sleep 1
pass "foreground/background + real trim-memory completed"

adb shell pm clear "$PKG" | tee "$OUT/pm-clear.txt"
adb shell cmd overlay enable --user 0 com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
adb shell settings put secure navigation_mode 2 >/dev/null 2>&1 || true
adb shell am start -W -n "$PKG/.MainActivity" -d "https://example.com/" | tee "$OUT/predictive-start.txt"
sleep 1
SIZE="$(adb shell wm size | tr -d "\r" | sed -n "s/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1 \2/p" | tail -1)"
read -r WIDTH HEIGHT <<< "$SIZE"
[[ -n "${WIDTH:-}" && -n "${HEIGHT:-}" ]] || fail "ukuran layar emulator tidak terbaca"
MIDY=$((HEIGHT / 2))
ENDX=$((WIDTH / 2))
adb shell input swipe 1 "$MIDY" "$ENDX" "$MIDY" 350
sleep 2
RESUMED="$(adb shell dumpsys activity activities | grep -m1 "mResumedActivity" || true)"
echo "RESUMED_AFTER_BACK=$RESUMED" | tee -a "$RESULTS"
if echo "$RESUMED" | grep -q "$PKG"; then
  fail "predictive back root tidak meninggalkan Activity"
fi
pass "predictive back root gesture returned to system"

adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb shell dumpsys activity exit-info "$PKG" > "$OUT/exit-info.txt" || true
adb shell dumpsys activity lastanr > "$OUT/last-anr.txt" || true

if grep -B4 -A8 "Process: $PKG" "$OUT/logcat.txt" | grep -q "FATAL EXCEPTION"; then
  fail "FATAL EXCEPTION target package ditemukan"
fi
if grep -Eq "ANR in $PKG|am_anr.*$PKG|Application Not Responding: $PKG" "$OUT/logcat.txt" "$OUT/last-anr.txt"; then
  fail "ANR target package ditemukan"
fi
if grep -Eq "REASON_(CRASH|ANR)" "$OUT/exit-info.txt"; then
  fail "exit-info mencatat crash/ANR"
fi
pass "Logcat/exit-info bebas fatal crash dan ANR"

echo "RUNTIME_GATE_API_${API}=PASS" | tee -a "$RESULTS"
cat "$RESULTS"
