#!/usr/bin/env bash
# Uji penjaga line-ending (.gitattributes) untuk NevusQuetta-Browser.
#
# Tujuan: memastikan atribut Git untuk berkas sensitif line-ending benar-benar
# aktif, sehingga checkout di Windows (core.autocrlf=true) tidak merusak skrip
# shell POSIX `B-Android/gradlew` maupun berkas biner.
#
# Keluar 0 bila semua pemeriksaan lulus, 1 bila ada yang gagal.
# Jalankan dari mana saja di dalam repo:  bash B-Android/scripts/test_line_ending_guard.sh

set -u

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT" || exit 1

total=0
fail=0

check() {
    local label="$1" expected="$2" actual="$3"
    total=$((total + 1))
    if [ "$expected" = "$actual" ]; then
        printf '  [PASS] %s\n' "$label"
    else
        printf '  [FAIL] %s (diharapkan=%s, dapat=%s)\n' "$label" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}

attr() {
    # attr <path> <attribute> -> nilai
    git check-attr "$2" -- "$1" 2>/dev/null | sed 's/^.*: //'
}

echo "=== Uji penjaga line-ending ==="

echo "-- 1. .gitattributes ada di akar repo --"
if [ -f "$REPO_ROOT/.gitattributes" ]; then
    check ".gitattributes ada" "yes" "yes"
else
    check ".gitattributes ada" "yes" "no"
fi

echo "-- 2. skrip shell POSIX wajib LF --"
for f in B-Android/gradlew; do
    check "$f text=set" "set" "$(attr "$f" text)"
    check "$f eol=lf" "lf" "$(attr "$f" eol)"
done

echo "-- 3. skrip Windows wajib CRLF --"
check "gradlew.bat eol=crlf" "crlf" "$(attr "gradlew.bat" eol)"

echo "-- 4. berkas biner wajib binary (text unset) --"
for f in \
    B-Android/gradle/wrapper/gradle-wrapper.jar \
    D-PWA/icons/icon-192.png \
    D-PWA/icons/icon-512.png \
    D-PWA/icons/icon-512-maskable.png
do
    if [ -e "$f" ]; then
        check "$f binary=set" "set" "$(attr "$f" binary)"
        check "$f text=unset" "unset" "$(attr "$f" text)"
    fi
done

echo "-- 5. berkas byte-sensitif wajib LF --"
for f in \
    C-Chromium/assets/nevus_ruleset.json.sha256 \
    C-Chromium/assets/nevus_ruleset.json
do
    if [ -e "$f" ]; then
        check "$f eol=lf" "lf" "$(attr "$f" eol)"
    fi
done

echo "-- 6. mode executable gradlew tetap 100755 --"
mode="$(git ls-files -s -- B-Android/gradlew | awk '{print $1}')"
check "B-Android/gradlew mode" "100755" "$mode"

echo "-- 7. .gitattributes sendiri tidak membuat normalisasi massal --"
# Hanya berkas TERLACAK yang diperiksa: berkas baru yang belum di-commit
# (mis. .gitattributes dan skrip uji ini sendiri) memang wajar muncul di status.
changed="$(git diff --name-only | wc -l | tr -d ' ')"
check "tidak ada berkas terlacak yang berubah" "0" "$changed"

echo
echo "HASIL: $((total - fail))/$total lulus, $fail gagal"
if [ "$fail" -eq 0 ]; then
    echo "STATUS: LULUS"
    exit 0
fi
echo "STATUS: GAGAL"
exit 1
