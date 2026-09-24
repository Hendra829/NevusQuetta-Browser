#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# NevusQuetta - pembuat "complete source snapshot" yang DETERMINISTIK,
# FAIL-CLOSED, dan TERVERIFIKASI-SENDIRI.
#
# Menggantikan blok `run: |` di .github/workflows/package-source-snapshot.yml.
# Temuan pada versi lama -> bagaimana versi ini menutupnya:
#
#   [B1] `mapfile -t x < <( find ... )` : kegagalan `find` TIDAK tertangkap
#        `set -e`/`pipefail` (proses-substitution selalu rc=0) sehingga gate
#        rahasia bisa LOLOS DIAM-DIAM.  -> di sini semua `find` menulis ke
#        berkas sebagai perintah biasa; kegagalannya menghentikan skrip.
#
#   [B2] `.env.*` ikut menandai berkas AMAN seperti `.env.example`
#        -> gate gagal palsu. -> daftar putih eksplisit (*.example/*.sample/
#        *.template/*.dist).
#
#   [B3] `rm -rf "$DIST"` atas nilai dari variabel lingkungan bisa berbahaya.
#        -> semua kerja di `mktemp -d` milik kita; tidak pernah `rm -rf` pada
#        direktori dari input.
#
#   [B4] Nama berkas ber-newline memecah daftar berbasis baris.
#        -> setiap operasi memakai NUL (`-print0`); nama ber-newline DITOLAK.
#
#   [B5] Arsip tidak pernah dibuka ulang: sha256sum hanya membuktikan "berkas
#        ada", bukan "berkas benar".  -> arsip dibuka lagi; daftar isinya harus
#        sama persis dengan manifest DAN isi setiap berkas di-hash ulang.
#        Inilah gate PASS (bukan exit code semata).
#
#   [B6] Cap waktu "sekarang" + mtime tulisan baru -> arsip tidak deterministik
#        (commit sama, SHA-256 berbeda) sehingga SHA256SUMS bukan identitas.
#        -> mtime dinormalkan ke waktu commit, cap waktu dibekukan, dan daftar
#        entri arsip diurutkan (LC_ALL=C).
#
# Pemakaian:
#   scripts/package_source_snapshot.sh [opsi]
#
#     --repo DIR      repositori (default: direktori kerja saat ini)
#     --ref REV       revisi yang dipaketkan (default: HEAD)
#     --out DIR       direktori keluaran (default: <workdir>/out)
#     --name FILE     nama arsip (default: nevusquetta-complete-source.zip)
#     --stamp ISO8601 cap waktu untuk SNAPSHOT-INFO.txt (default: dari commit)
#     --no-purge      jangan hapus arsip biner V0.8
#     --verify-only   verifikasi saja: butuh --archive dan --manifest
#     --archive FILE  arsip yang diverifikasi (mode --verify-only)
#     --manifest FILE manifest sumber  (mode --verify-only)
#     -h, --help
#
# Keluaran (baris key=value stabil untuk CI):
#   SNAPSHOT=PASS  ARCHIVE=...  MANIFEST=...  SHA256=...  FILES=...  VERIFY=PASS
# ---------------------------------------------------------------------------
set -Eeuo pipefail

PROG="$(basename "$0")"
log() { printf '[%s] %s\n' "$PROG" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$PROG" "$*" >&2; exit 1; }

# --- argumen ---------------------------------------------------------------
REPO_DIR="$PWD"
OUT_DIR=""
REF="HEAD"
ARCHIVE_NAME="nevusquetta-complete-source.zip"
STAMP=""
PURGE_V08=1
VERIFY_ONLY=0
VERIFY_ARCHIVE=""
VERIFY_MANIFEST=""

usage() { sed -n '2,52p' "$0" | sed 's/^# \{0,1\}//'; }

while (( $# > 0 )); do
  case "$1" in
    --repo)        (( $# >= 2 )) || die "--repo butuh nilai"; REPO_DIR="$2"; shift 2 ;;
    --ref)         (( $# >= 2 )) || die "--ref butuh nilai"; REF="$2"; shift 2 ;;
    --out)         (( $# >= 2 )) || die "--out butuh nilai"; OUT_DIR="$2"; shift 2 ;;
    --name)        (( $# >= 2 )) || die "--name butuh nilai"; ARCHIVE_NAME="$2"; shift 2 ;;
    --stamp)       (( $# >= 2 )) || die "--stamp butuh nilai"; STAMP="$2"; shift 2 ;;
    --no-purge)    PURGE_V08=0; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --archive)     (( $# >= 2 )) || die "--archive butuh nilai"; VERIFY_ARCHIVE="$2"; shift 2 ;;
    --manifest)    (( $# >= 2 )) || die "--manifest butuh nilai"; VERIFY_MANIFEST="$2"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "argumen tidak dikenal: $1 (lihat --help)" ;;
  esac
done

case "$ARCHIVE_NAME" in
  ""|*/*|*..*) die "nama arsip tidak valid: $ARCHIVE_NAME" ;;
esac

for bin in git find sort sha256sum zip unzip wc touch realpath; do
  command -v "$bin" >/dev/null 2>&1 || die "perkakas wajib tidak ditemukan: $bin"
done

# --------------------------------------------------------------------------
# Nama berkas yang harus DITOLAK (rahasia / material penandatanganan).
# Mengembalikan 0 = tolak, 1 = aman.
# --------------------------------------------------------------------------
is_forbidden_name() {
  local base="$1"
  # Templat/contoh yang aman: sengaja diizinkan (memperbaiki B2).
  case "$base" in
    *.example|*.sample|*.dist) return 1 ;;
    *.template|*.tmpl)         return 1 ;;
  esac
  case "$base" in
    .env|.env.*|*.env)                                   return 0 ;;
    *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.p8)       return 0 ;;
    local.properties|secrets.properties)                 return 0 ;;
    credentials.json|credentials.yml|credentials.yaml)   return 0 ;;
    service-account.json|service_account.json)           return 0 ;;
    id_rsa|id_dsa|id_ecdsa|id_ed25519|known_hosts)       return 0 ;;
    .npmrc|.pypirc|.netrc|.htpasswd)                     return 0 ;;
  esac
  return 1
}

# --------------------------------------------------------------------------
# Tolak jalur arsip yang absolut / memakai traversal, per-komponen.
# (Menghindari pola glob yang saling menutupi -> bersih di shellcheck.)
# --------------------------------------------------------------------------
reject_unsafe_entry() {
  local entry="$1"
  case "$entry" in
    "") die "entri arsip kosong" ;;
    /*) die "entri arsip absolut: $entry" ;;
  esac
  local IFS='/'
  local -a comps=()
  read -r -a comps <<< "$entry"
  local comp
  for comp in "${comps[@]}"; do
    [[ "$comp" == ".." ]] && die "entri arsip memakai traversal: $entry"
  done
  return 0
}

# --------------------------------------------------------------------------
# VERIFIKASI: buka ulang arsip dan bandingkan dengan manifest.
# --------------------------------------------------------------------------
verify_archive_against_manifest() {
  local archive="$1" manifest="$2"

  [[ -s "$archive" ]]  || die "arsip tidak ada/kosong: $archive"
  [[ -s "$manifest" ]] || die "manifest tidak ada/kosong: $manifest"

  # 1. keutuhan arsip menurut unzip sendiri
  if ! unzip -t "$archive" > "$WORK/ziptest.txt" 2>&1; then
    cat "$WORK/ziptest.txt" >&2
    die "arsip rusak (unzip -t gagal)"
  fi

  # 2. daftar berkas di dalam arsip (satu baris = satu entri)
  unzip -Z1 "$archive" > "$WORK/ziplist.raw" || die "tidak bisa membaca daftar arsip"
  : > "$WORK/ziplist.files"
  local entry
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    case "$entry" in
      */) continue ;;                                  # entri direktori
    esac
    reject_unsafe_entry "$entry"
    printf '%s\n' "$entry" >> "$WORK/ziplist.files"
  done < "$WORK/ziplist.raw"

  # 3. gate rahasia pada ARSIP (bukan hanya pada staging) - versi lama tidak
  #    pernah memeriksa hasil akhir seperti ini
  while IFS= read -r entry; do
    if is_forbidden_name "${entry##*/}"; then
      die "arsip memuat berkas rahasia/penandatangan: $entry"
    fi
  done < "$WORK/ziplist.files"

  # 4. hanya baris data manifest: "<sha256>  <path>"
  grep -E '^[0-9a-fA-F]{64}  ' "$manifest" > "$WORK/manifest.rows" || true
  [[ -s "$WORK/manifest.rows" ]] || die "manifest tidak punya baris data yang valid"
  sed 's/^[0-9a-fA-F]\{64\}  //' "$WORK/manifest.rows" | LC_ALL=C sort > "$WORK/manifest.paths"
  LC_ALL=C sort "$WORK/ziplist.files" > "$WORK/archive.paths"

  local n_manifest n_archive
  n_manifest="$(wc -l < "$WORK/manifest.paths")"
  n_archive="$(wc -l < "$WORK/archive.paths")"
  (( n_manifest > 0 )) || die "manifest kosong - tidak ada yang bisa diverifikasi"

  if ! diff -u "$WORK/manifest.paths" "$WORK/archive.paths" > "$WORK/paths.diff"; then
    head -n 40 "$WORK/paths.diff" >&2
    die "daftar berkas arsip TIDAK sama dengan manifest (manifest=$n_manifest arsip=$n_archive)"
  fi

  # 5. ISI setiap berkas di dalam arsip harus cocok dengan hash di manifest.
  local mismatch=0 checked=0 want got path
  while IFS= read -r line; do
    want="${line%%  *}"
    path="${line#*  }"
    got="$(unzip -p "$archive" "$path" | sha256sum | cut -d' ' -f1)"
    checked=$((checked + 1))
    if [[ "$got" != "$want" ]]; then
      printf 'HASH TIDAK COCOK: %s\n  manifest=%s\n  arsip   =%s\n' "$path" "$want" "$got" >&2
      mismatch=$((mismatch + 1))
    fi
  done < "$WORK/manifest.rows"

  (( mismatch == 0 )) || die "$mismatch berkas tidak cocok dengan manifest"
  VERIFY_FILES="$checked"
  return 0
}

# --------------------------------------------------------------------------
# MODE VERIFIKASI SAJA (read-only, tidak butuh repositori)
# --------------------------------------------------------------------------
if (( VERIFY_ONLY == 1 )); then
  [[ -n "$VERIFY_ARCHIVE" ]]  || die "--verify-only butuh --archive"
  [[ -n "$VERIFY_MANIFEST" ]] || die "--verify-only butuh --manifest"
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/nq-verify.XXXXXXXX")" || die "mktemp gagal"
  trap 'rm -rf -- "$WORK" 2>/dev/null || true' EXIT
  verify_archive_against_manifest "$VERIFY_ARCHIVE" "$VERIFY_MANIFEST"
  printf 'VERIFY=PASS\nVERIFIED_FILES=%s\nARCHIVE=%s\n' "$VERIFY_FILES" "$VERIFY_ARCHIVE"
  exit 0
fi

# --- repositori ------------------------------------------------------------
REPO_DIR="$(realpath -e -- "$REPO_DIR" 2>/dev/null)" || die "--repo tidak bisa di-resolve: $REPO_DIR"
git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1 || die "bukan repositori git: $REPO_DIR"
git -C "$REPO_DIR" rev-parse --verify --quiet "${REF}^{commit}" >/dev/null \
  || die "ref tidak valid atau tidak ada: $REF"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/nq-package.XXXXXXXX")" || die "mktemp gagal"
trap 'rm -rf -- "$WORK" 2>/dev/null || true' EXIT
STAGE="$WORK/stage"
mkdir -p -- "$STAGE"

if [[ -n "$OUT_DIR" ]]; then
  mkdir -p -- "$OUT_DIR" || die "tidak bisa membuat --out: $OUT_DIR"
  OUT_DIR="$(realpath -e -- "$OUT_DIR")" || die "--out tidak bisa di-resolve"
  [[ "$OUT_DIR" != "/" ]] || die "--out tidak boleh /"
  [[ "$OUT_DIR" != "$REPO_DIR" ]] || die "--out tidak boleh sama dengan repositori"
  case "$OUT_DIR" in "$REPO_DIR"/*) die "--out tidak boleh berada di dalam repositori" ;; esac
else
  OUT_DIR="$WORK/out"
  mkdir -p -- "$OUT_DIR"
fi

COMMIT_SHA="$(git -C "$REPO_DIR" rev-parse "$REF")"
COMMIT_EPOCH="$(git -C "$REPO_DIR" log -1 --format=%ct "$REF")"

if [[ -z "$STAMP" ]]; then
  if [[ -n "${SOURCE_DATE_EPOCH:-}" ]]; then
    STAMP="$(date -u -d "@${SOURCE_DATE_EPOCH}" +%Y-%m-%dT%H:%M:%SZ)"
  else
    STAMP="$(date -u -d "@${COMMIT_EPOCH}" +%Y-%m-%dT%H:%M:%SZ)"
  fi
fi

log "repo=$REPO_DIR"
log "ref=$REF commit=$COMMIT_SHA epoch=$COMMIT_EPOCH stamp=$STAMP"

# --- 1. ekstraksi dari REVISI (bukan working tree) -------------------------
git -C "$REPO_DIR" archive --format=tar "$REF" | tar -xf - -C "$STAGE"

# --- 2. tolak .git nyasar dan symlink (fail-closed) ------------------------
find "$STAGE" -name '.git' -print0 > "$WORK/dotgit.nul" || die "pemindaian .git gagal"
if [[ -s "$WORK/dotgit.nul" ]]; then
  tr '\0' '\n' < "$WORK/dotgit.nul" >&2
  die "direktori .git bocor ke staging"
fi
find "$STAGE" -type l -print0 > "$WORK/symlinks.nul" || die "pemindaian symlink gagal"
if [[ -s "$WORK/symlinks.nul" ]]; then
  tr '\0' '\n' < "$WORK/symlinks.nul" >&2
  die "symlink ditemukan: arsip sumber tidak boleh memuat symlink (bisa menunjuk keluar arsip)"
fi

# --- 3. inventaris (JALUR ABSOLUT; `find` sebagai perintah biasa -> B1) ----
find "$STAGE" -type f -print0 > "$WORK/all.nul" || die "inventaris find gagal"
FILE_COUNT="$(tr -cd '\0' < "$WORK/all.nul" | wc -c)"
(( FILE_COUNT > 0 )) || die "staging kosong: tidak ada berkas untuk dipaketkan"
log "berkas di staging: $FILE_COUNT"

# --- 4. nama ber-newline/CR ditolak (B4) ----------------------------------
while IFS= read -r -d '' p; do
  case "$p" in
    *$'\n'*|*$'\r'*) die "nama berkas memuat karakter baris (ilegal): $(printf '%q' "$p")" ;;
  esac
done < "$WORK/all.nul"

# --- 5. gate rahasia pada staging (fail-closed) ---------------------------
: > "$WORK/forbidden.lst"
while IFS= read -r -d '' p; do
  if is_forbidden_name "${p##*/}"; then
    printf '%s\n' "${p#"$STAGE"/}" >> "$WORK/forbidden.lst"
  fi
done < "$WORK/all.nul"
if [[ -s "$WORK/forbidden.lst" ]]; then
  log "GAGAL: berkas rahasia/penandatangan terdeteksi:"
  sed 's/^/  - /' "$WORK/forbidden.lst" >&2
  die "keluarkan berkas tersebut dari revisi sebelum memaketkan"
fi
log "gate rahasia: BERSIH ($FILE_COUNT berkas diperiksa)"

# --- 6. purge arsip biner usang DENGAN JEJAK (absolut -> aman) ------------
PURGED_COUNT=0
if (( PURGE_V08 == 1 )); then
  find "$STAGE" -type f \( \
      -iname '*v0.8*.zip' -o -iname '*v0.8*.tar' -o -iname '*v0.8*.tar.gz' \
      -o -iname '*v0.8*.tgz' -o -iname '*v0.8*.7z' \) -print0 > "$WORK/purge.nul" \
    || die "pemindaian purge gagal"
  if [[ -s "$WORK/purge.nul" ]]; then
    tr '\0' '\n' < "$WORK/purge.nul" | sed "s|^$STAGE/||" > "$OUT_DIR/PURGED-FILES.txt"
    PURGED_COUNT="$(wc -l < "$OUT_DIR/PURGED-FILES.txt")"
    xargs -0 rm -f -- < "$WORK/purge.nul"
    log "menghapus $PURGED_COUNT arsip biner usang (daftar: PURGED-FILES.txt)"
  fi
fi

# --- 7. SNAPSHOT-INFO.txt --------------------------------------------------
{
  echo "NevusQuetta complete source snapshot"
  echo "Repository: ${GITHUB_REPOSITORY:-$(git -C "$REPO_DIR" config --get remote.origin.url || echo unknown)}"
  echo "Snapshot ref: ${REF}"
  echo "Snapshot commit: ${COMMIT_SHA}"
  echo "Generated UTC: ${STAMP}"
  echo "Source files: ${FILE_COUNT}"
  echo "Purged obsolete archives: ${PURGED_COUNT}"
  echo
  echo "Included: all tracked source/workflows/scripts/docs/infra/configuration."
  echo "Excluded: secrets, private keys, signing keystores, caches, build outputs,"
  echo "          node_modules, obsolete V0.8 binary archives."
  echo "Verified by: scripts/package_source_snapshot.sh (open-and-compare)"
} > "$STAGE/SNAPSHOT-INFO.txt"

# --- 8. normalisasi waktu -> determinisme (B6) ----------------------------
find "$STAGE" -exec touch -h -d "@${COMMIT_EPOCH}" {} +
export SOURCE_DATE_EPOCH="$COMMIT_EPOCH"

# --- 9. manifest berurut + daftar entri arsip berurut ---------------------
: > "$WORK/rels.txt"
: > "$WORK/rows.txt"
while IFS= read -r -d '' p; do
  rel="${p#"$STAGE"/}"
  printf '%s\n' "$rel" >> "$WORK/rels.txt"
  printf '%s  %s\n' "$(sha256sum -- "$p" | cut -d' ' -f1)" "$rel" >> "$WORK/rows.txt"
done < "$WORK/all.nul"

MANIFEST="$OUT_DIR/SOURCE-FILES.sha256"
{
  printf '%s\n' "# sha256  path   (dibuat oleh scripts/package_source_snapshot.sh)"
  LC_ALL=C sort -k2,2 "$WORK/rows.txt"
  # SNAPSHOT-INFO.txt dibuat SETELAH inventaris, jadi dicatat eksplisit
  printf '%s  %s\n' "$(sha256sum -- "$STAGE/SNAPSHOT-INFO.txt" | cut -d' ' -f1)" "SNAPSHOT-INFO.txt"
} > "$WORK/manifest.tmp"
{
  head -n 1 "$WORK/manifest.tmp"
  tail -n +2 "$WORK/manifest.tmp" | LC_ALL=C sort -k2,2
} > "$MANIFEST"
MANIFEST_FILES="$(grep -Ec '^[0-9a-f]{64}  ' "$MANIFEST" || true)"
(( MANIFEST_FILES > 0 )) || die "manifest kosong"
grep -Eq '^[0-9a-f]{64}  SNAPSHOT-INFO\.txt$' "$MANIFEST" \
  || die "manifest tidak memuat SNAPSHOT-INFO.txt (inventaris tidak konsisten)"
log "manifest: $MANIFEST_FILES berkas -> $MANIFEST"

# daftar entri untuk arsip (urut -> arsip deterministik)
{ cat "$WORK/rels.txt"; printf '%s\n' "SNAPSHOT-INFO.txt"; } | LC_ALL=C sort > "$WORK/zipnames.txt"

# --- 10. arsip dari daftar berurut (deterministik; tanpa entri direktori) --
ARCHIVE="$OUT_DIR/$ARCHIVE_NAME"
rm -f -- "$ARCHIVE"
( cd "$STAGE" && zip -X -q -@ "$ARCHIVE" < "$WORK/zipnames.txt" ) || die "zip gagal"

# --- 11. VERIFIKASI SETELAH PEMBUATAN == gate PASS (B5) -------------------
verify_archive_against_manifest "$ARCHIVE" "$MANIFEST"
log "verifikasi arsip: PASS ($VERIFY_FILES berkas, daftar & hash isi cocok)"

( cd "$OUT_DIR" && sha256sum "$ARCHIVE_NAME" > SHA256SUMS.txt )
ARCHIVE_SHA="$(cut -d' ' -f1 "$OUT_DIR/SHA256SUMS.txt")"

# --- 12. ringkasan mesin-terbaca ------------------------------------------
printf '\n'
printf 'SNAPSHOT=PASS\n'
printf 'ARCHIVE=%s\n' "$ARCHIVE"
printf 'MANIFEST=%s\n' "$MANIFEST"
printf 'SHA256=%s\n' "$ARCHIVE_SHA"
printf 'FILES=%s\n' "$MANIFEST_FILES"
printf 'VERIFY=PASS\n'
