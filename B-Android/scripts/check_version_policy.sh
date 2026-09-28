#!/usr/bin/env bash
#
# check_version_policy.sh — penjaga konsistensi kebijakan versi NevusQuetta.
#
# LATAR MASALAH
# -------------
# Versi rilis NevusQuetta dideklarasikan di TIGA tempat berbeda dan tidak ada
# yang menjaga ketiganya tetap sinkron:
#
#   1. B-Android/app/build.gradle.kts          (versionCode / versionName)
#   2. .github/workflows/android-v09cd-release.yml
#        - langkah "aapt dump badging" meng-assert versi APK
#        - langkah gate runtime meng-assert versi terpasang via dumpsys
#   3. STATUS.md                                (dokumen status rilis)
#
# Akibat nyata yang sudah terjadi: STATUS.md menyatakan `0.9.0-ab`
# (`versionCode 900`) sementara build.gradle.kts sudah `0.9.0-cd-rc1`
# (`versionCode 910`), dan label itu menunjuk kode yang gate Android-nya
# BELUM PERNAH hijau. Label rilis yang tidak dapat dibuktikan adalah cacat
# kebijakan rilis, bukan sekadar dokumen basi.
#
# Skrip ini menutup kelas kesalahan itu: ia membaca versi dari
# build.gradle.kts (satu-satunya sumber kebenaran) lalu MEMASTIKAN seluruh
# penegasan di tempat lain sepakat. Gagal-keras (exit 1) bila ada yang
# menyimpang, sehingga penyimpangan tidak bisa masuk diam-diam.
#
# Pemakaian:
#   bash B-Android/scripts/check_version_policy.sh [ROOT_REPO]
#
set -Eeuo pipefail

ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
GRADLE="$ROOT/B-Android/app/build.gradle.kts"
RELEASE_WF="$ROOT/.github/workflows/android-v09cd-release.yml"
STATUS_MD="$ROOT/STATUS.md"

total=0
fail=0

check() {
    local label="$1" expected="$2" actual="$3"
    total=$((total + 1))
    if [[ "$expected" == "$actual" ]]; then
        printf '  OK    %s: %s\n' "$label" "$actual"
    else
        printf '  GAGAL %s: diharapkan "%s", ditemukan "%s"\n' "$label" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}

contains() {
    local label="$1" needle="$2" file="$3"
    total=$((total + 1))
    if [[ ! -f "$file" ]]; then
        printf '  GAGAL %s: berkas tidak ada: %s\n' "$label" "$file"
        fail=$((fail + 1))
        return
    fi
    if grep -qF -- "$needle" "$file"; then
        printf '  OK    %s: "%s" ditemukan di %s\n' "$label" "$needle" "$(basename "$file")"
    else
        printf '  GAGAL %s: "%s" TIDAK ditemukan di %s\n' "$label" "$needle" "$file"
        fail=$((fail + 1))
    fi
}

echo "== Penjaga kebijakan versi NevusQuetta =="
echo "   root: $ROOT"

# ---------------------------------------------------------------------------
# 1. Sumber kebenaran: build.gradle.kts
# ---------------------------------------------------------------------------
[[ -f "$GRADLE" ]] || { echo "GAGAL: $GRADLE tidak ada" >&2; exit 1; }

CODE="$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE" | head -1 | grep -oE '[0-9]+$')"
NAME="$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"

[[ -n "$CODE" ]] || { echo "GAGAL: versionCode tidak terbaca dari $GRADLE" >&2; exit 1; }
[[ -n "$NAME" ]] || { echo "GAGAL: versionName tidak terbaca dari $GRADLE" >&2; exit 1; }

echo
echo "== Sumber kebenaran (B-Android/app/build.gradle.kts) =="
echo "   versionCode = $CODE"
echo "   versionName = $NAME"

# ---------------------------------------------------------------------------
# 2. Sanity bentuk: versionCode harus bilangan positif, versionName semver-ish
# ---------------------------------------------------------------------------
echo
echo "== Bentuk nilai =="
total=$((total + 1))
if [[ "$CODE" =~ ^[1-9][0-9]*$ ]]; then
    printf '  OK    versionCode bilangan positif: %s\n' "$CODE"
else
    printf '  GAGAL versionCode bukan bilangan positif: %s\n' "$CODE"; fail=$((fail + 1))
fi

total=$((total + 1))
if [[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    printf '  OK    versionName berbentuk versi: %s\n' "$NAME"
else
    printf '  GAGAL versionName tidak berbentuk versi: %s\n' "$NAME"; fail=$((fail + 1))
fi

# CATATAN: tidak ada penjaga yang memetakan versionCode -> versionName secara
# aritmetis. Percobaan pertama skrip ini memakai heuristik "minor = versionCode
# tanpa 2 digit terakhir", dan heuristik itu SALAH untuk nilai nyata repo ini
# (910 -> minor 9, sedangkan versionName sah-nya `0.9.0-cd-rc1`). Aturan
# turunan yang dikarang lalu menuduh nilai yang benar sebagai pelanggaran akan
# membuat gate ini tidak dapat dipercaya, jadi heuristik itu dibuang. Yang
# benar-benar penting dan dapat dibuktikan hanyalah: versi yang SAMA dipakai di
# seluruh tempat (diperiksa di bagian workflow dan STATUS.md di bawah).

# ---------------------------------------------------------------------------
# 3. Penegasan di workflow rilis harus memakai versi yang sama
# ---------------------------------------------------------------------------
echo
echo "== Penegasan workflow rilis (.github/workflows/android-v09cd-release.yml) =="
contains "assert aapt versionCode" "versionCode='$CODE'" "$RELEASE_WF"
contains "assert aapt versionName" "versionName='$NAME'" "$RELEASE_WF"
contains "assert dumpsys versionCode" "versionCode=$CODE" "$RELEASE_WF"
contains "assert dumpsys versionName" "versionName=$NAME" "$RELEASE_WF"

# ---------------------------------------------------------------------------
# 4. STATUS.md harus menyebut versi yang sama
# ---------------------------------------------------------------------------
echo
echo "== STATUS.md =="
contains "versionCode di STATUS.md" "versionCode $CODE" "$STATUS_MD"
contains "versionName di STATUS.md" "$NAME" "$STATUS_MD"

# ---------------------------------------------------------------------------
echo
echo "== Ringkasan =="
echo "pemeriksaan : $total"
echo "gagal       : $fail"
if [[ "$fail" -eq 0 ]]; then
    echo "HASIL       : LULUS"
    exit 0
fi
echo "HASIL       : GAGAL"
exit 1
