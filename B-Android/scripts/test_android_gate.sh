#!/usr/bin/env bash
# ===========================================================================
# Uji logika skrip gate Android TANPA toolchain Android sungguhan.
#
# Tujuan: membuktikan tiga hal yang tidak dapat dibuktikan dengan menjalankan
# gate di lingkungan tanpa toolchain:
#   1. jalur "semua LULUS"          -> exit 0, ringkasan semua LULUS
#   2. jalur "ada tahap GAGAL"      -> exit 1, ringkasan menandai tahap gagal
#   3. jalur "argumen salah"        -> exit 3
#
# Caranya: membuat direktori tiruan berisi `gradlew`, `java`, dan pohon SDK
# palsu, lalu menjalankan skrip gate dengan PATH/JAVA_HOME/ANDROID_HOME diarahkan
# ke tiruan tersebut. Tidak ada berkas repo yang diubah.
# ===========================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/run_android_gate.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
check() {
  if [ "$2" = "$3" ]; then
    printf '  [LULUS] %s (diharapkan %s, didapat %s)\n' "$1" "$3" "$2"
    PASS=$((PASS + 1))
  else
    printf '  [GAGAL] %s (diharapkan %s, didapat %s)\n' "$1" "$3" "$2"
    FAIL=$((FAIL + 1))
  fi
}

build_fake_toolchain() {
  local mode="$1"   # 0 = semua lulus, 1 = lintDebug gagal
  local root="$WORK/tc-$mode"
  mkdir -p "$root/bin" "$root/sdk/licenses" "$root/sdk/ndk/27.2.12479018" \
           "$root/sdk/cmake/3.22.1" "$root/module"

  # java palsu yang melaporkan versi 17
  cat >"$root/bin/java" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "-version" ]; then echo 'openjdk version "17.0.11" 2024-04-16' >&2; fi
exit 0
EOF
  chmod +x "$root/bin/java"

  # gradlew palsu: menulis log, dengan satu tahap gagal bila diminta
  cat >"$root/module/gradlew" <<EOF
#!/usr/bin/env bash
task="\${1:-}"
echo "> Task :\$task"
if [ "$mode" = "1" ] && [ "\$task" = "lintDebug" ]; then
  echo "error: lint menemukan kesalahan"
  exit 1
fi
echo "BUILD SUCCESSFUL"
exit 0
EOF
  chmod +x "$root/module/gradlew"

  # skrip gate disalin ke <module>/scripts/ agar layout sesuai harapan
  mkdir -p "$root/module/scripts"
  cp "$GATE" "$root/module/scripts/run_android_gate.sh"
  chmod +x "$root/module/scripts/run_android_gate.sh"
  printf '%s' "$root"
}

run_case() {
  local root="$1" logdir="$2"
  shift 2
  env -i PATH="$root/bin:/usr/bin:/bin" HOME="$WORK" \
      JAVA_HOME="$root" ANDROID_HOME="$root/sdk" \
      bash "$root/module/scripts/run_android_gate.sh" --log-dir "$logdir" "$@"
}

printf '== Uji skrip gate Android dengan toolchain tiruan ==\n'

# --- Kasus 1: semua tahap lulus -------------------------------------------
printf '\n-- kasus 1: semua tahap LULUS --\n'
ROOT1="$(build_fake_toolchain 0)"
OUT1="$(run_case "$ROOT1" "$WORK/log1" --quick 2>&1)"
RC1=$?
check "exit code semua lulus" "$RC1" "0"
printf '%s\n' "$OUT1" | grep -q 'HASIL AKHIR: SEMUA TAHAP LULUS'
check "ringkasan menyatakan semua lulus" "$?" "0"
printf '%s\n' "$OUT1" | grep -q 'clean *LULUS'
check "baris tahap clean LULUS" "$?" "0"

# --- Kasus 2: satu tahap gagal --------------------------------------------
printf '\n-- kasus 2: lintDebug GAGAL --\n'
ROOT2="$(build_fake_toolchain 1)"
OUT2="$(run_case "$ROOT2" "$WORK/log2" --quick 2>&1)"
RC2=$?
check "exit code ada kegagalan" "$RC2" "1"
printf '%s\n' "$OUT2" | grep -q 'HASIL AKHIR: ADA TAHAP YANG GAGAL'
check "ringkasan menyatakan ada kegagalan" "$?" "0"
printf '%s\n' "$OUT2" | grep -q 'lintDebug *GAGAL'
check "baris tahap lintDebug GAGAL" "$?" "0"
printf '%s\n' "$OUT2" | grep -q 'clean *LULUS'
check "tahap lain tetap dijalankan" "$?" "0"
test -s "$WORK/log2/2_lintDebug.log"
check "log per tahap tertulis" "$?" "0"

# --- Kasus 3: prasyarat tidak lengkap (tanpa java) -------------------------
printf '\n-- kasus 3: prasyarat tidak lengkap --\n'
mkdir -p "$WORK/none" "$WORK/none/module/scripts"
cp "$GATE" "$WORK/none/module/scripts/run_android_gate.sh"
OUT3="$(env -i PATH="/usr/bin:/bin" HOME="$WORK" \
        bash "$WORK/none/module/scripts/run_android_gate.sh" 2>&1)"
RC3=$?
check "exit code prasyarat" "$RC3" "2"
printf '%s\n' "$OUT3" | grep -q 'GAGAL PRASYARAT'
check "pesan prasyarat jelas" "$?" "0"
printf '%s\n' "$OUT3" | grep -q 'Tidak ada tahap build yang dijalankan'
check "tidak ada tahap dijalankan" "$?" "0"

# --- Kasus 4: argumen salah -----------------------------------------------
printf '\n-- kasus 4: argumen tidak dikenal --\n'
ROOT4="$(build_fake_toolchain 0)"
OUT4="$(run_case "$ROOT4" "$WORK/log4" --tidak-ada 2>&1)"
RC4=$?
check "exit code argumen salah" "$RC4" "3"

printf '\n== Ringkasan uji skrip gate ==\n'
printf 'lulus : %d\n' "$PASS"
printf 'gagal : %d\n' "$FAIL"
if [ "$FAIL" -eq 0 ]; then
  printf 'HASIL : LULUS\n'
  exit 0
fi
printf 'HASIL : GAGAL\n'
exit 1
