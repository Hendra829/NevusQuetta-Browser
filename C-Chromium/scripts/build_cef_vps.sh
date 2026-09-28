#!/usr/bin/env bash
# ===========================================================================
# build_cef_vps.sh — build aplikasi Chromium (CEF) NevusQuetta di VPS Linux.
#
# Skrip ini SIAP JALAN di VPS Ubuntu/Debian x86_64. Ia TIDAK dapat dijalankan
# di lingkungan berbatas (sandbox 8 GiB, tanpa distribusi CEF) — lihat
# "PRASYARAT" di bawah. Skrip memeriksa prasyarat LEBIH DULU dan berhenti
# dengan pesan jelas (exit 2) alih-alih menghasilkan artefak yang tidak sah.
#
# PRASYARAT (VPS):
#   - RAM   : >= 16 GB (32 GB disarankan)
#   - Disk  : >= 100 GB kosong  (checkout + output build sangat besar)
#   - CPU   : >= 4 core
#   - OS    : Linux x86_64
#   - Paket : cmake >= 3.22.1, ninja-build, git, python3, curl, unzip, bzip2,
#             clang atau g++, libgtk-3-dev, libnss3-dev, libasound2-dev
#
#   sudo apt-get update
#   sudo apt-get install -y cmake ninja-build git python3 curl unzip bzip2 \
#        build-essential libgtk-3-dev libnss3-dev libasound2-dev
#
# CARA PAKAI:
#   C-Chromium/scripts/build_cef_vps.sh                 # unduh CEF + build Release
#   C-Chromium/scripts/build_cef_vps.sh --cef-root DIR  # pakai CEF yang sudah ada
#   C-Chromium/scripts/build_cef_vps.sh --lab           # build lab (ruleset tanpa ttd)
#   C-Chromium/scripts/build_cef_vps.sh --verify-only   # hanya verifikasi hasil build
#
# KODE KELUAR:
#   0  build + verifikasi LULUS
#   1  build atau verifikasi GAGAL
#   2  prasyarat tidak terpenuhi (tidak ada tahap build dijalankan)
#   3  pemakaian argumen salah
# ===========================================================================
set -u -o pipefail

# Versi CEF yang dipakai proyek (>= 120 disyaratkan CMakeLists.txt).
CEF_VERSION_DEFAULT="120.1.10+g6b9c1d1+chromium-120.0.6099.129"
CEF_ROOT=""
LAB=0
VERIFY_ONLY=0
JOBS="$(nproc 2>/dev/null || echo 4)"

while [ $# -gt 0 ]; do
  case "$1" in
    --cef-root) CEF_ROOT="${2:-}"; [ -n "$CEF_ROOT" ] || { echo "GALAT: --cef-root butuh argumen" >&2; exit 3; }; shift 2 ;;
    --lab) LAB=1; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --jobs) JOBS="${2:-}"; [ -n "$JOBS" ] || { echo "GALAT: --jobs butuh argumen" >&2; exit 3; }; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "GALAT: argumen tidak dikenal: $1" >&2; exit 3 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/.." && pwd)"
BUILD_DIR="$MODULE_DIR/build-cef"

note() { printf '[build-cef] %s\n' "$*"; }
die()  { printf 'GALAT: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Prasyarat — gagal JELAS sebelum tahap apa pun
# ---------------------------------------------------------------------------
PREREQ=0
chk() { printf '  [X] %s\n' "$1" >&2; PREREQ=$((PREREQ + 1)); }
ok()  { printf '  [v] %s\n' "$1" >&2; }

printf '== Pemeriksaan prasyarat build CEF ==\n' >&2
printf 'modul : %s\n' "$MODULE_DIR" >&2

for tool in cmake ninja git python3 curl; do
  if command -v "$tool" >/dev/null 2>&1; then ok "$tool: $(command -v "$tool")"
  else chk "$tool tidak ditemukan (lihat PRASYARAT di bagian atas skrip)"; fi
done

if command -v clang++ >/dev/null 2>&1; then ok "compiler: clang++"
elif command -v g++ >/dev/null 2>&1; then ok "compiler: g++"
else chk "tidak ada clang++/g++"; fi

# Ruang disk: CEF butuh >= 100 GiB.
AVAIL_KB="$(df -Pk "$MODULE_DIR" | awk 'NR==2 {print $4}')"
MIN_KB=$((100 * 1024 * 1024))
if [ -z "$AVAIL_KB" ]; then chk "ruang disk tidak dapat dibaca"
elif [ "$AVAIL_KB" -lt "$MIN_KB" ]; then
  chk "ruang disk tersisa $((AVAIL_KB / 1024 / 1024)) GiB; CEF memerlukan >= 100 GiB"
else ok "ruang disk: $((AVAIL_KB / 1024 / 1024)) GiB tersisa"; fi

# RAM
if [ -r /proc/meminfo ]; then
  MEM_GB=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))
  if [ "$MEM_GB" -lt 16 ]; then chk "RAM ${MEM_GB} GiB; CEF disarankan >= 16 GiB"
  else ok "RAM: ${MEM_GB} GiB"; fi
fi

if [ "$PREREQ" -gt 0 ]; then
  printf '\nGAGAL PRASYARAT: %d masalah. Tidak ada tahap build dijalankan.\n' "$PREREQ" >&2
  printf 'Pasang prasyarat sesuai PRASYARAT di bagian atas skrip, lalu jalankan ulang.\n' >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 2. Unduh distribusi CEF bila belum ada
# ---------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 0 ] && [ -z "$CEF_ROOT" ]; then
  CEF_ROOT="$HOME/cef_binary_${CEF_VERSION_DEFAULT}_linux64"
  if [ ! -d "$CEF_ROOT" ]; then
    note "mengunduh CEF $CEF_VERSION_DEFAULT (besar, puluhan menit)"
    TARBALL="/tmp/cef_binary_${CEF_VERSION_DEFAULT}_linux64.tar.bz2"
    curl -fL -o "$TARBALL" \
      "https://cef-builds.spotifycdn.com/cef_binary_${CEF_VERSION_DEFAULT}_linux64.tar.bz2" \
      || die "unduhan CEF gagal"
    # Verifikasi checksum resmi — WAJIB, jangan lewati.
    curl -fL -o "$TARBALL.sha1" \
      "https://cef-builds.spotifycdn.com/cef_binary_${CEF_VERSION_DEFAULT}_linux64.tar.bz2.sha1" \
      || die "unduhan checksum CEF gagal"
    EXPECTED="$(cut -d' ' -f1 "$TARBALL.sha1")"
    ACTUAL="$(sha1sum "$TARBALL" | cut -d' ' -f1)"
    [ "$EXPECTED" = "$ACTUAL" ] || die "checksum CEF TIDAK COCOK (harap $EXPECTED, dapat $ACTUAL)"
    note "checksum CEF cocok: $ACTUAL"
    tar -xjf "$TARBALL" -C "$HOME" || die "ekstraksi CEF gagal"
    rm -f "$TARBALL" "$TARBALL.sha1"
  else
    note "CEF sudah ada: $CEF_ROOT"
  fi
fi

# ---------------------------------------------------------------------------
# 3. Konfigurasi & build
# ---------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  [ -n "$CEF_ROOT" ] || die "CEF_ROOT kosong"
  [ -f "$CEF_ROOT/cmake/cef_variables.cmake" ] \
    || die "CEF_ROOT='$CEF_ROOT' bukan distribusi CEF lengkap (tidak ada cmake/cef_variables.cmake)"

  CMAKE_ARGS=(-S "$MODULE_DIR" -B "$BUILD_DIR" -G Ninja
              -DCMAKE_BUILD_TYPE=Release -DCEF_ROOT="$CEF_ROOT")
  [ "$LAB" -eq 1 ] && CMAKE_ARGS+=(-DNQ_ALLOW_UNSIGNED_RULESET=ON)

  note "konfigurasi: cmake ${CMAKE_ARGS[*]}"
  cmake "${CMAKE_ARGS[@]}" || die "konfigurasi cmake gagal"

  note "build: cmake --build $BUILD_DIR -j$JOBS"
  cmake --build "$BUILD_DIR" --config Release -j"$JOBS" || die "build gagal"
fi

# ---------------------------------------------------------------------------
# 4. Verifikasi hasil build (WAJIB — lihat README-BUILD.md §6)
# ---------------------------------------------------------------------------
BIN="$BUILD_DIR/NevusQuetta"
[ -x "$BIN" ] || die "biner tidak ditemukan: $BIN"

FAIL=0
note "6.1 biner ada dan dapat dijalankan"
file "$BIN" || FAIL=1
if ldd "$BIN" 2>/dev/null | grep -i "not found"; then
  printf '  [X] pustaka hilang pada ldd\n' >&2; FAIL=1
else
  printf '  [v] ldd: tidak ada pustaka hilang\n' >&2
fi

note "6.2 sumber daya CEF di samping biner"
for f in icudtl.dat chrome_100_percent.pak chrome_200_percent.pak resources.pak \
         snapshot_blob.bin v8_context_snapshot.bin; do
  if [ -e "$BUILD_DIR/$f" ]; then printf '  [v] %s\n' "$f" >&2
  else printf '  [X] HILANG: %s\n' "$f" >&2; FAIL=1; fi
done

note "6.3 versi Chromium yang tertaut"
"$BIN" --version 2>&1 | head -3 || true

note "6.4 uji perilaku (butuh Xvfb/layar)"
if command -v xvfb-run >/dev/null 2>&1; then
  timeout 20 xvfb-run -a "$BIN" --user-data-dir=/tmp/nq-verify >/tmp/nq.log 2>&1 || true
  if grep -Ei "failed to load resources" /tmp/nq.log; then
    printf '  [X] ada "failed to load resources"\n' >&2; FAIL=1
  else
    printf '  [v] tidak ada "failed to load resources"\n' >&2
  fi
else
  printf '  [!] xvfb-run tidak ada — uji perilaku DILEWATI (bukan lulus)\n' >&2
fi

if [ "$FAIL" -eq 0 ]; then
  echo "BUILD_CEF=PASS"
  echo "BIN=$BIN"
  exit 0
else
  echo "BUILD_CEF=FAIL"
  exit 1
fi
