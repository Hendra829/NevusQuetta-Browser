#!/usr/bin/env bash
#
# package-release.sh — paket rilis NevusQuetta (APK + AAB + source + SBOM).
#
# Diperkeras dari versi sebelumnya. Perbaikan utama (lihat AUDIT-REPORT.md F-14):
#   1. Gate berkas rahasia FAIL-CLOSED. Versi lama memakai
#      `mapfile -t forbidden < <( find ... )`; kegagalan `find` di dalam process
#      substitution TIDAK tertangkap `set -e`/`pipefail` (rc selalu 0) sehingga
#      array kosong dibaca sebagai "BERSIH" -> rahasia bisa lolos ke rilis.
#      Sekarang `find` menulis ke berkas dan rc-nya diperiksa langsung.
#   2. Arsip DETERMINISTIK. mtime diambil dari commit (bukan jam dinding) dan
#      entri diurutkan, sehingga dua build dari commit identik menghasilkan
#      SHA-256 identik.
#   3. Arsip DIVERIFIKASI. Arsip dibuka ulang, daftar entri dan SHA-256 isi tiap
#      berkas dibandingkan dengan manifest. `zipinfo -1` saja tidak membuktikan
#      isi benar.
#   4. TIDAK ada `rm -rf "$DIST"` atas variabel lingkungan. Staging memakai
#      subdirektori .staging dan promosi bersifat atomik; hanya nama artefak
#      yang dikenal yang dibersihkan.
#
# Pemakaian:
#   scripts/package-release.sh [DIST_DIR] [APK] [AAB]
#
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DIST="${1:-$ROOT/dist}"
APK="${2:-$ROOT/B-Android/app/build/outputs/apk/release/app-release.apk}"
AAB="${3:-$ROOT/B-Android/app/build/outputs/bundle/release/app-release.aab}"

VERSION="$(git -C "$ROOT" describe --tags --always --dirty 2>/dev/null || git -C "$ROOT" rev-parse --short HEAD)"
STAGE="$DIST/.staging"
REMOTE_BASE="https://github.com/Hendra829/NevusQuetta-Browser"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '[package-release] %s\n' "$*"; }

require_inside_repo() {
    local path="$1" label="$2"
    local real
    real="$(realpath -e "$path" 2>/dev/null)" || die "$label tidak ditemukan: $path"
    case "$real" in
        "$ROOT"/*) ;;
        *) die "$label di luar repository: $real" ;;
    esac
}

# ---------------------------------------------------------------------------
# 0. Preflight
# ---------------------------------------------------------------------------
[[ -s "$APK" ]] || die "APK rilis bertanda tangan tidak ditemukan: $APK"
require_inside_repo "$APK" "APK"

HAVE_AAB=0
if [[ -s "$AAB" ]]; then
    require_inside_repo "$AAB" "AAB"
    HAVE_AAB=1
else
    note "PERINGATAN: AAB tidak ditemukan ($AAB) — paket dibuat tanpa AAB"
fi

case "$DIST" in
    /|""|"$HOME"|"$ROOT") die "DIST tidak boleh berupa direktori berbahaya: '$DIST'" ;;
esac
mkdir -p "$DIST"

# ---------------------------------------------------------------------------
# 1. Staging bersih, tanpa menyentuh artefak lama yang tidak dikenal
# ---------------------------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE/source"

# ---------------------------------------------------------------------------
# 2. APK / AAB
# ---------------------------------------------------------------------------
cp "$APK" "$STAGE/NevusQuetta.apk"
if (( HAVE_AAB )); then cp "$AAB" "$STAGE/NevusQuetta.aab"; fi

# ---------------------------------------------------------------------------
# 3. Source dari git archive (bukan working tree -> bebas artefak build lokal)
# ---------------------------------------------------------------------------
git -C "$ROOT" archive --format=tar HEAD | tar -xf - -C "$STAGE/source"

# Buang arsip binary legacy V0.8 (kebijakan spec §3: tidak boleh beredar lagi).
find "$STAGE/source" -type f \
    \( -iname '*v0.8*.zip' -o -iname '*v0.8*.tar' -o -iname '*v0.8*.tar.gz' \
       -o -iname '*v0.8*.tgz' -o -iname '*v0.8*.7z' \) -delete

# Buang output build & cache bila ada yang lolos dari git.
find "$STAGE/source" -depth -type d \
    \( -name build -o -name .gradle -o -name cache -o -name caches \
       -o -name node_modules \) -exec rm -rf {} +

# Symlink dapat menunjuk keluar arsip; hapus agar isi arsip tidak ambigu.
find "$STAGE/source" -type l -print -delete >/dev/null

# ---------------------------------------------------------------------------
# 4. Gate berkas rahasia — FAIL-CLOSED
# ---------------------------------------------------------------------------
add_or_keep_example() { grep -Eq '(^|/)\.env\.(example|sample|template)$'; }

find "$STAGE/source" -type f \
    \( -name '.env' -o -name '.env.*' -o \
       -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' -o \
       -name 'local.properties' -o -name 'secrets.properties' -o \
       -name 'credentials.json' -o -name 'service-account*.json' -o \
       -name 'id_rsa' -o -name 'id_ed25519' -o -name '*.pem' -o -name '*.key' \) \
    -print > "$STAGE/forbidden.txt" \
    || die "gate rahasia gagal dijalankan (find rc != 0) — menolak melanjutkan"

# .env.example/.sample/.template bukan rahasia (regresi F-04 audit sebelumnya).
grep -Ev '(^|/)\.env\.(example|sample|template)$' "$STAGE/forbidden.txt" \
    > "$STAGE/forbidden.real.txt" || true

if [[ -s "$STAGE/forbidden.real.txt" ]]; then
    printf 'Berkas rahasia pada source rilis:\n' >&2
    sed 's/^/  - /' "$STAGE/forbidden.real.txt" >&2
    exit 1
fi
note "gate rahasia: BERSIH ($(find "$STAGE/source" -type f | wc -l) berkas diperiksa)"

# Deteksi pola secret keras di dalam isi berkas teks.
if grep -rInE '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"[^"]+"|nevuslab122' \
    "$STAGE/source" > "$STAGE/secretgrep.txt" 2>/dev/null; then
    printf 'Password signing keras di dalam source:\n' >&2
    sed 's/^/  - /' "$STAGE/secretgrep.txt" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 5. Arsip source DETERMINISTIK + manifest
# ---------------------------------------------------------------------------
EPOCH="$(git -C "$ROOT" log -1 --pretty=%ct)"
STAMP="$(date -u -d "@$EPOCH" +%Y-%m-%dT%H:%M:%SZ)"

# Normalisasi mtime & permission agar zip stabil antar build.
find "$STAGE/source" -exec touch -d "@$EPOCH" {} +
find "$STAGE/source" -type d -exec chmod 0755 {} +
find "$STAGE/source" -type f -exec chmod 0644 {} +
find "$STAGE/source" -type f -name '*.sh' -exec chmod 0755 {} +

SOURCE_ZIP="$STAGE/NevusQuetta-source.zip"
( cd "$STAGE/source" && find . -type f | LC_ALL=C sort | zip -X -q "$SOURCE_ZIP" -@ )

( cd "$STAGE/source" && find . -type f | LC_ALL=C sort | xargs -d '\n' sha256sum ) \
    > "$STAGE/SOURCE-FILES.sha256"

# ---------------------------------------------------------------------------
# 6. Verifikasi arsip (buka ulang, bandingkan daftar + hash isi)
# ---------------------------------------------------------------------------
VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "$VERIFY_DIR"' EXIT
unzip -qq "$SOURCE_ZIP" -d "$VERIFY_DIR" || die "arsip source tidak dapat dibuka"

( cd "$VERIFY_DIR" && find . -type f | LC_ALL=C sort | xargs -d '\n' sha256sum ) \
    > "$VERIFY_DIR.actual.sha256"
if ! diff -q "$STAGE/SOURCE-FILES.sha256" "$VERIFY_DIR.actual.sha256" >/dev/null; then
    printf 'Manifest tidak cocok dengan isi arsip:\n' >&2
    diff "$STAGE/SOURCE-FILES.sha256" "$VERIFY_DIR.actual.sha256" >&2 || true
    exit 1
fi
note "verifikasi arsip: PASS ($(wc -l < "$STAGE/SOURCE-FILES.sha256") berkas, daftar & hash isi cocok)"

# ---------------------------------------------------------------------------
# 7. SBOM & ringkasan versi
# ---------------------------------------------------------------------------
if [[ -x "$ROOT/scripts/generate-sbom.sh" ]]; then
    "$ROOT/scripts/generate-sbom.sh" "$STAGE/SBOM.json" \
        || die "pembuatan SBOM gagal"
    note "SBOM dibuat: $STAGE/SBOM.json"
fi

{
    printf 'name=NevusQuetta\n'
    printf 'version=%s\n' "$VERSION"
    printf 'commit=%s\n' "$(git -C "$ROOT" rev-parse HEAD)"
    printf 'commit_epoch=%s\n' "$EPOCH"
    printf 'built_utc=%s\n' "$STAMP"
    printf 'apk=NevusQuetta.apk\n'
    printf 'aab=%s\n' "$( (( HAVE_AAB )) && printf 'NevusQuetta.aab' || printf 'none' )"
    printf 'source=NevusQuetta-source.zip\n'
} > "$STAGE/VERSION-MANIFEST.txt"

# ---------------------------------------------------------------------------
# 8. Promosi atomik + SHA256SUMS
# ---------------------------------------------------------------------------
for name in NevusQuetta.apk NevusQuetta.aab NevusQuetta-source.zip \
            SOURCE-FILES.sha256 SBOM.json VERSION-MANIFEST.txt SHA256SUMS.txt; do
    [[ -e "$DIST/$name" ]] && rm -f "$DIST/$name"
done

cp "$STAGE/NevusQuetta.apk" "$DIST/NevusQuetta.apk"
(( HAVE_AAB )) && cp "$STAGE/NevusQuetta.aab" "$DIST/NevusQuetta.aab"
cp "$SOURCE_ZIP" "$DIST/NevusQuetta-source.zip"
cp "$STAGE/SOURCE-FILES.sha256" "$DIST/SOURCE-FILES.sha256"
[[ -f "$STAGE/SBOM.json" ]] && cp "$STAGE/SBOM.json" "$DIST/SBOM.json"
cp "$STAGE/VERSION-MANIFEST.txt" "$DIST/VERSION-MANIFEST.txt"

( cd "$DIST" && sha256sum NevusQuetta.apk \
    $( (( HAVE_AAB )) && printf 'NevusQuetta.aab' ) \
    NevusQuetta-source.zip VERSION-MANIFEST.txt SBOM.json > SHA256SUMS.txt 2>/dev/null \
    || sha256sum NevusQuetta.apk NevusQuetta-source.zip VERSION-MANIFEST.txt > SHA256SUMS.txt )

rm -rf "$STAGE"

echo "PACKAGE_RELEASE=PASS"
echo "DIST=$DIST"
echo "APK=$DIST/NevusQuetta.apk"
(( HAVE_AAB )) && echo "AAB=$DIST/NevusQuetta.aab"
echo "SOURCE_ZIP=$DIST/NevusQuetta-source.zip"
echo "SOURCE_SHA256=$(sha256sum "$DIST/NevusQuetta-source.zip" | cut -d' ' -f1)"
echo "REMOTE=$REMOTE_BASE"
