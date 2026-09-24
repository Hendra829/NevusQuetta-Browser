#!/usr/bin/env bash
# ===========================================================================
# Gate build Android untuk NevusQuetta (modul B-Android) di VPS Ubuntu.
#
# Tujuan: menjalankan SELURUH gate (clean, unit test, lint, assemble, bundle)
# dalam satu perintah, dengan pemeriksaan prasyarat toolchain LEBIH DULU,
# log per tahap, ringkasan lulus/gagal, dan exit code yang benar.
#
# PRASYARAT TOOLCHAIN DI VPS UBUNTU (jalankan sebagai root/sudo):
#
#   # 1. JDK 17 (AGP 8.x mensyaratkan JDK 17)
#   sudo apt-get update
#   sudo apt-get install -y openjdk-17-jdk unzip curl
#   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
#
#   # 2. Android command line tools + SDK/NDK (versi persis dipakai repo ini)
#   mkdir -p "$HOME/android-sdk/cmdline-tools" && cd "$HOME/android-sdk/cmdline-tools"
#   curl -fsSLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
#   unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest 2>/dev/null || true
#   export ANDROID_HOME="$HOME/android-sdk"
#   export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
#   yes | sdkmanager --licenses
#   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
#              "ndk;27.2.12479018" "cmake;3.22.1"
#
#   # 3. Gradle TIDAK perlu dipasang: repo memakai gradle wrapper (8.11.1).
#   #    Wrapper butuh akses jaringan ke services.gradle.org pada pemakaian pertama.
#
#   # 4. (opsional) agar kredensial rilis tersedia untuk bundleRelease:
#   #    siapkan keystore + gradle.properties sesuai B-Android/README rilis.
#
# CARA PAKAI:
#   ./scripts/run_android_gate.sh                 # gate lengkap
#   ./scripts/run_android_gate.sh --quick         # tanpa assembleRelease/bundleRelease
#   ./scripts/run_android_gate.sh --log-dir /tmp/nq-gate
#
# KODE KELUAR:
#   0  semua tahap yang dijalankan LULUS
#   1  ada tahap yang GAGAL
#   2  prasyarat toolchain tidak terpenuhi (tidak ada tahap yang dijalankan)
#   3  pemakaian argumen salah
# ===========================================================================
set -u -o pipefail

QUICK=0
LOG_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1; shift ;;
    --log-dir)
      LOG_DIR="${2:-}"
      if [ -z "$LOG_DIR" ]; then
        printf 'GALAT: --log-dir memerlukan argumen jalur.\n' >&2
        exit 3
      fi
      shift 2
      ;;
    -h|--help)
      sed -n '2,50p' "$0"
      exit 0
      ;;
    *)
      printf 'GALAT: argumen tidak dikenal: %s\n' "$1" >&2
      exit 3
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo: B-Android/scripts/run_android_gate.sh -> root modul = B-Android
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$MODULE_DIR" || exit 2

if [ -z "$LOG_DIR" ]; then
  LOG_DIR="$MODULE_DIR/build/gate-logs"
fi
mkdir -p "$LOG_DIR" || { printf 'GALAT: tidak dapat membuat direktori log %s\n' "$LOG_DIR" >&2; exit 2; }

# ---------------------------------------------------------------------------
# Pemeriksaan prasyarat — gagal JELAS sebelum tahap apa pun dijalankan
# ---------------------------------------------------------------------------
PREREQ_ERRORS=0

note_prereq() { printf '  [X] %s\n' "$1" >&2; PREREQ_ERRORS=$((PREREQ_ERRORS + 1)); }
ok_prereq()   { printf '  [v] %s\n' "$1" >&2; }

printf '== Pemeriksaan prasyarat toolchain Android ==\n' >&2
printf 'modul    : %s\n' "$MODULE_DIR" >&2
printf 'log      : %s\n' "$LOG_DIR" >&2

# 1. Java
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
if [ -z "$JAVA_BIN" ]; then
  note_prereq "java tidak ditemukan (pasang: sudo apt-get install -y openjdk-17-jdk)"
else
  JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
  if [ -z "$JAVA_MAJOR" ]; then
    note_prereq "versi java tidak dapat dibaca dari: $JAVA_BIN"
  elif [ "$JAVA_MAJOR" -lt 17 ]; then
    note_prereq "JDK $JAVA_MAJOR terlalu lama; AGP 8.x memerlukan JDK 17 atau lebih baru ($JAVA_BIN)"
  else
    ok_prereq "java: $JAVA_BIN (versi $JAVA_MAJOR)"
  fi
fi

# 2. Android SDK
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  note_prereq "ANDROID_HOME / ANDROID_SDK_ROOT tidak diset"
else
  SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  if [ ! -d "$SDK" ]; then
    note_prereq "direktori SDK tidak ada: $SDK"
  else
    ok_prereq "android sdk: $SDK"
  fi
  if [ ! -f "$SDK/local.properties" ] 2>/dev/null; then :; fi
  if [ ! -d "$SDK/licenses" ]; then
    note_prereq "lisensi SDK belum diterima (jalankan: yes | sdkmanager --licenses)"
  else
    ok_prereq "lisensi SDK tersedia"
  fi
  NDK_REQUIRED="27.2.12479018"
  if [ ! -d "$SDK/ndk/$NDK_REQUIRED" ]; then
    note_prereq "NDK $NDK_REQUIRED tidak ditemukan di $SDK/ndk (sdkmanager \"ndk;$NDK_REQUIRED\")"
  else
    ok_prereq "ndk: $NDK_REQUIRED"
  fi
  if [ ! -d "$SDK/cmake/3.22.1" ]; then
    note_prereq "CMake 3.22.1 tidak ditemukan di $SDK/cmake (sdkmanager \"cmake;3.22.1\")"
  else
    ok_prereq "cmake: 3.22.1"
  fi
fi

# 3. Gradle wrapper (repo memakai wrapper, bukan gradle sistem)
if [ ! -x "$MODULE_DIR/gradlew" ]; then
  note_prereq "gradlew tidak ada atau tidak dapat dieksekusi di $MODULE_DIR"
else
  ok_prereq "gradle wrapper: $MODULE_DIR/gradlew"
fi

# 4. Ruang disk. Ambangnya BERGANTUNG pada cakupan tahap: gate lengkap
#    (termasuk assembleRelease + bundleRelease) jauh lebih berat daripada --quick.
#    Kekurangan ruang disk TIDAK boleh memblokir gate --quick, dan pada gate
#    lengkap ia diperlakukan sebagai peringatan kecuali benar-benar kritis.
AVAIL_KB="$(df -Pk "$MODULE_DIR" | awk 'NR==2 {print $4}')"
MIN_HARD_KB=2097152      # 2 GiB: di bawah ini build dipastikan gagal
MIN_FULL_KB=8388608      # 8 GiB: disarankan untuk gate lengkap
if [ -z "$AVAIL_KB" ]; then
  ok_prereq "ruang disk: tidak dapat dibaca (dilewati)"
elif [ "$AVAIL_KB" -lt "$MIN_HARD_KB" ]; then
  note_prereq "ruang disk tersisa $((AVAIL_KB / 1024)) MiB; minimal 2 GiB diperlukan"
elif [ "$QUICK" -eq 1 ]; then
  ok_prereq "ruang disk: $((AVAIL_KB / 1024)) MiB tersisa (cukup untuk --quick)"
elif [ "$AVAIL_KB" -lt "$MIN_FULL_KB" ]; then
  ok_prereq "ruang disk: $((AVAIL_KB / 1024)) MiB tersisa"
  printf '  [!] PERINGATAN: gate lengkap disarankan >= 8 GiB; assembleRelease/bundleRelease mungkin gagal.\n' >&2
else
  ok_prereq "ruang disk: $((AVAIL_KB / 1024)) MiB tersisa"
fi

if [ "$PREREQ_ERRORS" -gt 0 ]; then
  printf '\nGAGAL PRASYARAT: %d masalah di atas. Tidak ada tahap build yang dijalankan.\n' "$PREREQ_ERRORS" >&2
  printf 'Pasang prasyarat sesuai petunjuk di bagian atas skrip ini, lalu jalankan ulang.\n' >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# Daftar tahap
# ---------------------------------------------------------------------------
STAGE_NAMES=()
STAGE_TASKS=()
STAGE_RESULTS=()
STAGE_SECONDS=()

add_stage() { STAGE_NAMES+=("$1"); STAGE_TASKS+=("$2"); }

add_stage "clean"            "clean"
add_stage "testDebugUnitTest" "testDebugUnitTest"
add_stage "lintDebug"        "lintDebug"
add_stage "assembleDebug"    "assembleDebug"
if [ "$QUICK" -eq 0 ]; then
  add_stage "assembleRelease" "assembleRelease"
  add_stage "bundleRelease"   "bundleRelease"
fi

GRADLE_ARGS=(--no-daemon --stacktrace)
if [ -n "$JAVA_BIN" ]; then
  GRADLE_ARGS+=("-Dorg.gradle.java.home=$(dirname "$(dirname "$JAVA_BIN")")")
fi

printf '\n== Menjalankan %d tahap gradle ==\n' "${#STAGE_NAMES[@]}"
OVERALL=0
INDEX=0
while [ "$INDEX" -lt "${#STAGE_NAMES[@]}" ]; do
  NAME="${STAGE_NAMES[$INDEX]}"
  TASK="${STAGE_TASKS[$INDEX]}"
  LOG_FILE="$LOG_DIR/${INDEX}_${NAME}.log"
  printf '\n--- [%d/%d] %s ---\n' "$((INDEX + 1))" "${#STAGE_NAMES[@]}" "$NAME"
  START="$(date +%s)"
  # log lengkap ke berkas, ringkas ke layar
  if "$MODULE_DIR/gradlew" "$TASK" "${GRADLE_ARGS[@]}" >"$LOG_FILE" 2>&1; then
    RESULT="LULUS"
  else
    RESULT="GAGAL"
    OVERALL=1
  fi
  END="$(date +%s)"
  STAGE_RESULTS+=("$RESULT")
  STAGE_SECONDS+=("$((END - START))")
  printf '    hasil: %s (%ss)  log: %s\n' "$RESULT" "$((END - START))" "$LOG_FILE"
  if [ "$RESULT" = "GAGAL" ]; then
    printf '    --- 20 baris terakhir log ---\n'
    tail -n 20 "$LOG_FILE" | sed 's/^/    /'
  fi
  INDEX=$((INDEX + 1))
done

# ---------------------------------------------------------------------------
# Ringkasan
# ---------------------------------------------------------------------------
printf '\n==================== RINGKASAN GATE ANDROID ====================\n'
printf '%-22s %-8s %s\n' "TAHAP" "HASIL" "DURASI"
INDEX=0
while [ "$INDEX" -lt "${#STAGE_NAMES[@]}" ]; do
  printf '%-22s %-8s %ss\n' "${STAGE_NAMES[$INDEX]}" "${STAGE_RESULTS[$INDEX]}" "${STAGE_SECONDS[$INDEX]}"
  INDEX=$((INDEX + 1))
done
printf '================================================================\n'
if [ "$OVERALL" -eq 0 ]; then
  printf 'HASIL AKHIR: SEMUA TAHAP LULUS\n'
else
  printf 'HASIL AKHIR: ADA TAHAP YANG GAGAL (lihat log di %s)\n' "$LOG_DIR"
fi
exit "$OVERALL"
