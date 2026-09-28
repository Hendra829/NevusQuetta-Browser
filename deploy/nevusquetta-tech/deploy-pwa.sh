#!/usr/bin/env bash
# =============================================================================
# deploy-pwa.sh — unggah PWA NevusQuetta ke document root nevusquetta.tech
# =============================================================================
# Skrip ini SIAP JALAN tetapi BELUM PERNAH dijalankan dari lingkungan agen,
# karena tidak ada kredensial hosting di sana. Jalankan dari komputer/VPS Anda
# yang memang punya akses.
#
# Pemakaian:
#   ./deploy-pwa.sh --metode rsync  --host <user@host> [--tujuan /path]
#   ./deploy-pwa.sh --metode scp    --host <user@host> [--tujuan /path]
#   ./deploy-pwa.sh --metode ftp    --host <host> --user <u> [--tujuan /path]
#   ./deploy-pwa.sh --metode lokal  --tujuan /var/www/html
#   ./deploy-pwa.sh --metode periksa            # hanya verifikasi, tanpa unggah
#
# Opsi:
#   --kering     dry-run: tampilkan apa yang AKAN diunggah, tidak menulis
#   --paksa      lewati konfirmasi interaktif
#
# Keluar dengan kode:
#   0  berhasil (atau --kering selesai)
#   1  verifikasi pasca-unggah gagal
#   2  prasyarat tidak terpenuhi (perkakas/kredensial hilang)
# =============================================================================
set -uo pipefail

SUMBER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/public_html"
DOMAIN="nevusquetta.tech"
METODE=""
HOST=""
USER_FTP=""
TUJUAN=""
KERING=0
PAKSA=0

# Berkas yang WAJIB ada setelah unggah, beserta Content-Type yang diharapkan.
declare -a WAJIB=(
  "index.html|text/html"
  "manifest.json|application/manifest+json"
  "service-worker.js|javascript"
  "offline.html|text/html"
  "app.js|javascript"
  "styles.css|text/css"
  "icons/icon-192.png|image/png"
  "icons/icon-512.png|image/png"
  "icons/icon-512-maskable.png|image/png"
)

warn() { printf '\033[33m[!]\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[32m[v]\033[0m %s\n' "$*"; }
bad()  { printf '\033[31m[X]\033[0m %s\n' "$*" >&2; }
info() { printf '    %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 0. Baca argumen
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --metode) METODE="${2:-}"; shift 2 ;;
    --host)   HOST="${2:-}";   shift 2 ;;
    --user)   USER_FTP="${2:-}"; shift 2 ;;
    --tujuan) TUJUAN="${2:-}"; shift 2 ;;
    --kering) KERING=1; shift ;;
    --paksa)  PAKSA=1; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) bad "argumen tidak dikenal: $1"; exit 2 ;;
  esac
done

echo "=============================================================="
echo " Deploy PWA NevusQuetta -> $DOMAIN"
echo "=============================================================="
echo "sumber : $SUMBER"
echo "metode : ${METODE:-<belum dipilih>}"
echo "kering : $KERING"
echo

# ---------------------------------------------------------------------------
# 1. Prasyarat: sumber lengkap
# ---------------------------------------------------------------------------
if [ ! -d "$SUMBER" ]; then
  bad "direktori sumber tidak ada: $SUMBER"
  exit 2
fi

HILANG=0
for baris in "${WAJIB[@]}"; do
  f="${baris%%|*}"
  if [ ! -f "$SUMBER/$f" ]; then
    bad "berkas wajib hilang dari sumber: $f"
    HILANG=$((HILANG+1))
  fi
done
if [ ! -f "$SUMBER/.htaccess" ]; then
  warn ".htaccess tidak ada di sumber — aturan cache/redirect tidak akan terpasang"
fi
if [ "$HILANG" -gt 0 ]; then
  bad "sumber tidak lengkap ($HILANG berkas hilang). Batal."
  exit 2
fi
ok "sumber lengkap: $(find "$SUMBER" -type f | wc -l) berkas, $(du -sb "$SUMBER" | cut -f1) byte"

# Sidik jari sumber, untuk dibandingkan setelah unggah.
echo
echo "--- md5 sumber ---"
( cd "$SUMBER" && find . -type f | sort | xargs md5sum )
echo

# ---------------------------------------------------------------------------
# 2. Mode periksa: hanya verifikasi domain, tanpa unggah
# ---------------------------------------------------------------------------
if [ "$METODE" = "periksa" ]; then
  echo "--- verifikasi https://$DOMAIN ---"
  GAGAL=0
  for baris in "${WAJIB[@]}"; do
    f="${baris%%|*}"; ct_harap="${baris##*|}"
    hasil=$(curl -sS -o /dev/null -m 20 -w '%{http_code} %{content_type}' "https://$DOMAIN/$f" 2>&1)
    kode="${hasil%% *}"; ct="${hasil##* }"
    if [ "$kode" = "200" ] && printf '%s' "$ct" | grep -qi "$ct_harap"; then
      ok "$f  ->  $kode  $ct"
    else
      bad "$f  ->  $kode  $ct   (harap 200 + $ct_harap)"
      GAGAL=$((GAGAL+1))
    fi
  done
  echo
  if [ "$GAGAL" -eq 0 ]; then
    ok "SEMUA BERKAS WAJIB TERLAYANI DENGAN BENAR"
    exit 0
  fi
  bad "$GAGAL berkas belum benar di $DOMAIN"
  exit 1
fi

# ---------------------------------------------------------------------------
# 3. Prasyarat per metode
# ---------------------------------------------------------------------------
case "$METODE" in
  rsync)
    command -v rsync >/dev/null || { bad "rsync tidak terpasang"; exit 2; }
    [ -n "$HOST" ] || { bad "--host wajib untuk metode rsync"; exit 2; }
    TUJUAN="${TUJUAN:-/var/www/html/}"
    ;;
  scp)
    command -v scp >/dev/null || { bad "scp tidak terpasang"; exit 2; }
    [ -n "$HOST" ] || { bad "--host wajib untuk metode scp"; exit 2; }
    TUJUAN="${TUJUAN:-/var/www/html/}"
    ;;
  ftp)
    command -v lftp >/dev/null || { bad "lftp tidak terpasang (apt-get install -y lftp)"; exit 2; }
    [ -n "$HOST" ] || { bad "--host wajib untuk metode ftp"; exit 2; }
    [ -n "$USER_FTP" ] || { bad "--user wajib untuk metode ftp"; exit 2; }
    TUJUAN="${TUJUAN:-/public_html/}"
    ;;
  lokal)
    [ -n "$TUJUAN" ] || { bad "--tujuan wajib untuk metode lokal"; exit 2; }
    ;;
  "")
    bad "pilih --metode (rsync|scp|ftp|lokal|periksa)"
    exit 2
    ;;
  *)
    bad "metode tidak dikenal: $METODE"
    exit 2
    ;;
esac
ok "prasyarat metode '$METODE' terpenuhi"

# ---------------------------------------------------------------------------
# 4. Konfirmasi
# ---------------------------------------------------------------------------
if [ "$KERING" -eq 0 ] && [ "$PAKSA" -eq 0 ]; then
  echo
  warn "Ini akan MENULIS ke $DOMAIN (tujuan: $TUJUAN)."
  warn "Cadangkan isi lama lebih dulu bila ada."
  printf 'Lanjut? ketik "ya": '
  read -r jawab
  [ "$jawab" = "ya" ] || { echo "dibatalkan."; exit 0; }
fi

# ---------------------------------------------------------------------------
# 5. Unggah
# ---------------------------------------------------------------------------
echo
echo "--- unggah ---"
case "$METODE" in
  rsync)
    ARG=(-avz --delete --exclude='tests/')
    [ "$KERING" -eq 1 ] && ARG+=(--dry-run)
    rsync "${ARG[@]}" "$SUMBER/" "$HOST:$TUJUAN"
    RC=$?
    ;;
  scp)
    if [ "$KERING" -eq 1 ]; then
      info "DRY-RUN scp: berkas berikut akan dikirim ke $HOST:$TUJUAN"
      ( cd "$SUMBER" && find . -type f | sort )
      RC=0
    else
      ( cd "$SUMBER" && tar czf - . ) | ssh "$HOST" "mkdir -p '$TUJUAN' && tar xzf - -C '$TUJUAN'"
      RC=$?
    fi
    ;;
  ftp)
    ARG=(--only-newer)
    [ "$KERING" -eq 1 ] && ARG+=(--dry-run)
    lftp -u "$USER_FTP" "$HOST" -e "set ftp:ssl-allow yes; mirror -R ${ARG[*]} '$SUMBER' '$TUJUAN'; bye"
    RC=$?
    ;;
  lokal)
    if [ "$KERING" -eq 1 ]; then
      info "DRY-RUN lokal: rsync -av --delete '$SUMBER/' '$TUJUAN/'"
      RC=0
    else
      mkdir -p "$TUJUAN" && rsync -av --delete "$SUMBER/" "$TUJUAN/"
      RC=$?
    fi
    ;;
esac
echo "UNGGAH_EXIT=$RC"
if [ "$RC" -ne 0 ]; then
  bad "unggah gagal (exit $RC)"
  exit 2
fi
ok "unggah selesai"

if [ "$KERING" -eq 1 ]; then
  echo
  ok "DRY-RUN selesai — tidak ada berkas yang ditulis."
  exit 0
fi

# ---------------------------------------------------------------------------
# 6. Verifikasi pasca-unggah
# ---------------------------------------------------------------------------
# Metode 'lokal' menulis ke direktori, bukan ke domain — jadi verifikasinya
# membandingkan berkas di direktori tujuan, BUKAN melakukan curl ke domain.
# (Bug awal: verifikasi selalu curl ke domain, sehingga unggah lokal yang
#  sebenarnya berhasil selalu dilaporkan gagal.)
echo
echo "--- verifikasi pasca-unggah ---"
GAGAL=0
BEDA=0

if [ "$METODE" = "lokal" ]; then
  echo "mode: direktori lokal ($TUJUAN)"
  for baris in "${WAJIB[@]}"; do
    f="${baris%%|*}"
    if [ -f "$TUJUAN/$f" ]; then
      ok "$f  ->  ada"
    else
      bad "$f  ->  TIDAK ADA di $TUJUAN"
      GAGAL=$((GAGAL+1))
    fi
  done
  echo
  echo "--- bandingkan md5 sumber vs direktori tujuan ---"
  while read -r md5_src rel; do
    rel="${rel#./}"
    md5_dst=$(md5sum "$TUJUAN/$rel" 2>/dev/null | cut -d' ' -f1)
    if [ "$md5_src" = "$md5_dst" ]; then
      ok "cocok  $rel"
    else
      bad "BEDA   $rel  sumber=$md5_src  tujuan=${md5_dst:-<tidak ada>}"
      BEDA=$((BEDA+1))
    fi
  done < <( cd "$SUMBER" && find . -type f | sort | xargs md5sum )
else
  echo "mode: HTTPS (https://$DOMAIN)"
  sleep 3
  for baris in "${WAJIB[@]}"; do
    f="${baris%%|*}"; ct_harap="${baris##*|}"
    hasil=$(curl -sS -o /dev/null -m 20 -w '%{http_code} %{content_type}' "https://$DOMAIN/$f" 2>&1)
    kode="${hasil%% *}"; ct="${hasil##* }"
    if [ "$kode" = "200" ] && printf '%s' "$ct" | grep -qi "$ct_harap"; then
      ok "$f  ->  $kode  $ct"
    else
      bad "$f  ->  $kode  $ct   (harap 200 + $ct_harap)"
      GAGAL=$((GAGAL+1))
    fi
  done

  echo
  echo "--- bandingkan md5 sumber vs yang tersaji ---"
  while read -r md5_src rel; do
    rel="${rel#./}"
    md5_web=$(curl -sS -m 30 "https://$DOMAIN/$rel" 2>/dev/null | md5sum | cut -d' ' -f1)
    if [ "$md5_src" = "$md5_web" ]; then
      ok "cocok  $rel"
    else
      bad "BEDA   $rel  sumber=$md5_src  web=$md5_web"
      BEDA=$((BEDA+1))
    fi
  done < <( cd "$SUMBER" && find . -type f ! -name '.htaccess' | sort | xargs md5sum )
fi

echo
echo "=============================================================="
if [ "$GAGAL" -eq 0 ] && [ "$BEDA" -eq 0 ]; then
  if [ "$METODE" = "lokal" ]; then
    ok "SINKRONISASI BERHASIL — semua berkas ada di $TUJUAN dan md5 cocok"
    echo "Langkah berikutnya: arahkan document root web server ke $TUJUAN,"
    echo "lalu jalankan './deploy-pwa.sh --metode periksa' untuk memverifikasi HTTPS."
  else
    ok "SINKRONISASI BERHASIL — semua berkas terlayani dan md5 cocok"
    echo "Buka https://$DOMAIN/ di Chrome Android -> menu -> Tambahkan ke layar utama"
  fi
  exit 0
fi
bad "SINKRONISASI BELUM BENAR — $GAGAL berkas salah status, $BEDA berkas beda isi"
exit 1
