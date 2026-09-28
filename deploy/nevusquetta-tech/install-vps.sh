#!/usr/bin/env bash
# install-vps.sh — konfigurasi Nginx + publickey untuk nevusquetta.tech
# pada VPS Hostinger srv1990895 (187.53.143.103).
#
# Jalankan DI DALAM VPS sebagai root:
#   sudo bash install-vps.sh --kunci-publik "ssh-ed25519 AAAA... user@host"
#
# Skrip ini GAGAL-TERTUTUP: setiap langkah diperiksa, dan `nginx -t` WAJIB
# lulus SEBELUM reload. Bila `nginx -t` gagal, konfigurasi lama dipulihkan
# dan skrip keluar tanpa menyentuh nginx yang sedang berjalan.
#
# Kode keluar:
#   0  sukses
#   1  verifikasi gagal (konfigurasi sudah dipulihkan)
#   2  prasyarat tidak terpenuhi (tidak ada perubahan dilakukan)

set -uo pipefail

# ---------------------------------------------------------------------------
# Konfigurasi
# ---------------------------------------------------------------------------
DOMAIN="nevusquetta.tech"
WEBROOT="/var/www/${DOMAIN}"
CONF_AVAIL="/etc/nginx/sites-available/${DOMAIN}"
CONF_ENABLED="/etc/nginx/sites-enabled/${DOMAIN}"
SNIPPET="/etc/nginx/snippets/nevusquetta-security-headers.conf"
SUMBER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBKEY=""
PASANG_NGINX=1
PASANG_CERTBOT=1

# ---------------------------------------------------------------------------
# Warna & pembantu
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; B=$'\033[34m'; N=$'\033[0m'
else
  R=""; G=""; Y=""; B=""; N=""
fi
ok()   { printf '%s[v]%s %s\n' "$G" "$N" "$*"; }
bad()  { printf '%s[X]%s %s\n' "$R" "$N" "$*"; }
info() { printf '%s[i]%s %s\n' "$B" "$N" "$*"; }
warn() { printf '%s[!]%s %s\n' "$Y" "$N" "$*"; }
judul(){ printf '\n%s=== %s ===%s\n' "$B" "$*" "$N"; }

# ---------------------------------------------------------------------------
# Argumen
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --kunci-publik) PUBKEY="${2:-}"; shift 2 ;;
    --tanpa-pasang-nginx)   PASANG_NGINX=0; shift ;;
    --tanpa-pasang-certbot) PASANG_CERTBOT=0; shift ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) bad "argumen tidak dikenal: $1"; exit 2 ;;
  esac
done

judul "Prasyarat"
# 1. root
if [ "$(id -u)" -ne 0 ]; then
  bad "harus dijalankan sebagai root (sudo bash install-vps.sh ...)"
  exit 2
fi
ok "berjalan sebagai root"

# 2. sistem operasi
if [ -r /etc/os-release ]; then
  . /etc/os-release
  info "sistem: ${PRETTY_NAME:-tidak diketahui}"
fi

# 3. berkas sumber harus ada
for f in nginx-nevusquetta.tech.conf snippets/security-headers.conf; do
  if [ ! -f "$SUMBER/$f" ]; then
    bad "berkas sumber tidak ditemukan: $SUMBER/$f"
    exit 2
  fi
done
ok "berkas sumber lengkap"

# 4. nginx
if ! command -v nginx >/dev/null 2>&1; then
  if [ "$PASANG_NGINX" -eq 1 ]; then
    info "nginx belum terpasang — memasang..."
    if command -v apt-get >/dev/null 2>&1; then
      DEBIAN_FRONTEND=noninteractive apt-get update -qq && \
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx || {
        bad "pemasangan nginx gagal"; exit 2; }
    else
      bad "nginx tidak ada dan apt-get tidak tersedia"; exit 2
    fi
  else
    bad "nginx tidak terpasang (dan --tanpa-pasang-nginx dipakai)"; exit 2
  fi
fi
NGINX_VER="$(nginx -v 2>&1 | sed -n 's#.*nginx/\([0-9.]*\).*#\1#p')"
ok "nginx ${NGINX_VER}"

# 5. certbot (opsional — hanya untuk penerbitan sertifikat)
if ! command -v certbot >/dev/null 2>&1; then
  if [ "$PASANG_CERTBOT" -eq 1 ] && command -v apt-get >/dev/null 2>&1; then
    info "certbot belum terpasang — memasang..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq certbot python3-certbot-nginx \
      >/dev/null 2>&1 && ok "certbot terpasang" || warn "certbot gagal dipasang (lanjut)"
  else
    warn "certbot tidak tersedia — sertifikat harus sudah ada"
  fi
else
  ok "certbot tersedia"
fi

# 6. sertifikat TLS harus ada SEBELUM nginx -t, karena konfigurasi
#    mereferensikannya. Bila belum ada, terbitkan lebih dulu.
judul "Sertifikat TLS"
CERT_DIR="/etc/letsencrypt/live/${DOMAIN}"
if [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
  ok "sertifikat sudah ada di $CERT_DIR"
  openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -subject -enddate 2>/dev/null | sed 's/^/    /'
else
  warn "sertifikat belum ada di $CERT_DIR"
  if command -v certbot >/dev/null 2>&1; then
    info "menerbitkan sertifikat dengan certbot (webroot)..."
    mkdir -p "$WEBROOT"
    certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" -d "www.${DOMAIN}" \
      --non-interactive --agree-tos --register-unsafely-without-email 2>&1 | tail -5
    if [ -f "$CERT_DIR/fullchain.pem" ]; then
      ok "sertifikat berhasil diterbitkan"
    else
      bad "penerbitan sertifikat gagal — nginx TIDAK akan diubah"
      exit 2
    fi
  else
    bad "certbot tidak tersedia dan sertifikat belum ada — nginx TIDAK akan diubah"
    exit 2
  fi
fi

# ---------------------------------------------------------------------------
# Webroot
# ---------------------------------------------------------------------------
judul "Webroot"
mkdir -p "$WEBROOT"
ok "webroot siap: $WEBROOT"

# ---------------------------------------------------------------------------
# Snippet header keamanan
# ---------------------------------------------------------------------------
judul "Snippet header keamanan"
mkdir -p /etc/nginx/snippets
install -m 0644 "$SUMBER/snippets/security-headers.conf" "$SNIPPET"
ok "terpasang: $SNIPPET"

# ---------------------------------------------------------------------------
# Konfigurasi nginx — dengan pemulihan otomatis bila nginx -t gagal
# ---------------------------------------------------------------------------
judul "Konfigurasi nginx"
CADANGAN=""
if [ -f "$CONF_AVAIL" ]; then
  CADANGAN="${CONF_AVAIL}.bak.$(date -u +%Y%m%dT%H%M%SZ)"
  cp -a "$CONF_AVAIL" "$CADANGAN"
  info "cadangan konfigurasi lama: $CADANGAN"
fi

# Sesuaikan direktif HTTP/2 dengan versi nginx yang benar-benar terpasang.
#   nginx >= 1.25.1 -> 'http2 on;'  (bentuk modern)
#   nginx <  1.25.1 -> 'listen ... http2' (bentuk lama; 'http2 on;' = ERROR)
SEMENTARA="$(mktemp)"
cp "$SUMBER/nginx-nevusquetta.tech.conf" "$SEMENTARA"
MAYOR="$(printf '%s' "$NGINX_VER" | cut -d. -f1)"
MINOR="$(printf '%s' "$NGINX_VER" | cut -d. -f2)"
PATCH="$(printf '%s' "$NGINX_VER" | cut -d. -f3)"
PATCH="${PATCH:-0}"
if [ "${MAYOR:-0}" -gt 1 ] || { [ "${MAYOR:-0}" -eq 1 ] && [ "${MINOR:-0}" -gt 25 ]; } || \
   { [ "${MAYOR:-0}" -eq 1 ] && [ "${MINOR:-0}" -eq 25 ] && [ "${PATCH:-0}" -ge 1 ]; }; then
  sed -i 's/^\( *\)listen 443 ssl http2;/\1listen 443 ssl;\n\1http2 on;/; s/^\( *\)listen \[::\]:443 ssl http2;/\1listen [::]:443 ssl;\n\1http2 on;/' "$SEMENTARA"
  info "nginx ${NGINX_VER} >= 1.25.1 -> memakai 'http2 on;'"
else
  info "nginx ${NGINX_VER} < 1.25.1 -> memakai 'listen ... http2' (aman)"
fi

install -m 0644 "$SEMENTARA" "$CONF_AVAIL"
rm -f "$SEMENTARA"
ln -sfn "$CONF_AVAIL" "$CONF_ENABLED"
ok "terpasang: $CONF_AVAIL -> $CONF_ENABLED"

# UJI SEBELUM RELOAD — ini inti gagal-tertutup.
info "menguji konfigurasi dengan 'nginx -t'..."
if nginx -t 2>&1 | sed 's/^/    /'; then
  ok "'nginx -t' LULUS"
else
  bad "'nginx -t' GAGAL — konfigurasi baru DIBATALKAN"
  if [ -n "$CADANGAN" ]; then
    cp -a "$CADANGAN" "$CONF_AVAIL"
    ok "konfigurasi lama dipulihkan dari $CADANGAN"
  else
    rm -f "$CONF_ENABLED" "$CONF_AVAIL"
    warn "konfigurasi baru dihapus (tidak ada cadangan)"
  fi
  nginx -t 2>&1 | sed 's/^/    /'
  bad "nginx TIDAK direload — situs tetap seperti semula"
  exit 1
fi

# ---------------------------------------------------------------------------
# Publickey (SSH)
# ---------------------------------------------------------------------------
judul "Publickey SSH"
if [ -n "$PUBKEY" ]; then
  case "$PUBKEY" in
    ssh-ed25519\ *|ssh-rsa\ *|ecdsa-sha2-*\ *) ;;
    *) bad "format kunci publik tidak dikenal"; exit 2 ;;
  esac
  mkdir -p /root/.ssh
  chmod 700 /root/.ssh
  touch /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
  if grep -qF "$(printf '%s' "$PUBKEY" | awk '{print $2}')" /root/.ssh/authorized_keys 2>/dev/null; then
    ok "kunci sudah ada di authorized_keys (tidak digandakan)"
  else
    printf '%s\n' "$PUBKEY" >> /root/.ssh/authorized_keys
    ok "kunci ditambahkan ke /root/.ssh/authorized_keys"
  fi
  info "jumlah kunci sekarang: $(grep -c . /root/.ssh/authorized_keys)"
  # Pastikan autentikasi kunci publik diizinkan.
  if [ -f /etc/ssh/sshd_config ]; then
    if grep -qiE '^\s*PubkeyAuthentication\s+no' /etc/ssh/sshd_config; then
      warn "PubkeyAuthentication=no di sshd_config — mengaktifkan"
      sed -i 's/^\s*PubkeyAuthentication\s\+no/PubkeyAuthentication yes/I' /etc/ssh/sshd_config
      systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
    else
      ok "PubkeyAuthentication aktif (atau default)"
    fi
  fi
else
  warn "tidak ada --kunci-publik yang diberikan — langkah SSH dilewati"
  info "jalankan ulang dengan: --kunci-publik \"\$(cat kunci.pub)\""
fi

# ---------------------------------------------------------------------------
# Reload & verifikasi
# ---------------------------------------------------------------------------
judul "Reload nginx"
# Bila nginx BELUM berjalan (mis. baru dipasang, atau baru saja dihentikan),
# `reload` akan gagal. Deteksi dulu, lalu start bila perlu.
if pgrep -x nginx >/dev/null 2>&1; then
  if systemctl reload nginx 2>/dev/null; then
    ok "nginx direload (systemctl)"
  elif nginx -s reload 2>/dev/null; then
    ok "nginx direload (nginx -s reload)"
  else
    bad "reload nginx gagal"
    exit 1
  fi
else
  info "nginx belum berjalan — menyalakan"
  if systemctl start nginx 2>/dev/null; then
    ok "nginx dinyalakan (systemctl)"
  elif nginx 2>/dev/null; then
    ok "nginx dinyalakan (nginx)"
  else
    bad "menyalakan nginx gagal"
    exit 1
  fi
fi
sleep 1
if pgrep -x nginx >/dev/null 2>&1; then
  ok "proses nginx aktif (pid: $(pgrep -x nginx | tr '\n' ' '))"
else
  bad "nginx tidak berjalan setelah reload/start"
  exit 1
fi

judul "Verifikasi"
GAGAL=0
cek() { # cek <url> <harapan-kode> <harapan-ct-substring>
  local url="$1" harap="$2" ct="$3" out kode ctype
  out="$(curl -sS -k -o /dev/null -w '%{http_code} %{content_type}' -H "Host: ${DOMAIN}" "$url" 2>/dev/null)"
  kode="${out%% *}"; ctype="${out#* }"
  if [ "$kode" = "$harap" ] && { [ -z "$ct" ] || printf '%s' "$ctype" | grep -qF "$ct"; }; then
    ok "$url -> $kode $ctype"
  else
    bad "$url -> $kode $ctype (harap $harap ${ct:+$ct})"
    GAGAL=$((GAGAL+1))
  fi
}
cek "https://127.0.0.1/"                200 "text/html"
cek "https://127.0.0.1/manifest.json"  200 "application/manifest+json"
cek "https://127.0.0.1/service-worker.js" 200 "application/javascript"

# Header keamanan harus ada di SETIAP respons.
for u in / /manifest.json /service-worker.js; do
  n=$(curl -sS -k -D - -o /dev/null -H "Host: ${DOMAIN}" "https://127.0.0.1$u" 2>/dev/null \
      | grep -ciE 'x-content-type-options|x-frame-options|referrer-policy|permissions-policy')
  if [ "$n" -eq 4 ]; then ok "header keamanan $u -> 4/4"
  else bad "header keamanan $u -> $n/4"; GAGAL=$((GAGAL+1)); fi
done

# www -> apex
loc=$(curl -sS -k -o /dev/null -w '%{redirect_url}' -H "Host: www.${DOMAIN}" https://127.0.0.1/ 2>/dev/null)
case "$loc" in
  "https://${DOMAIN}/"*) ok "www -> apex: $loc" ;;
  *) bad "www -> apex salah: '$loc'"; GAGAL=$((GAGAL+1)) ;;
esac

judul "Ringkasan"
if [ "$GAGAL" -eq 0 ]; then
  ok "SEMUA VERIFIKASI LULUS"
  info "langkah berikutnya: unggah isi public_html/ ke $WEBROOT"
  info "  rsync -av public_html/ root@${DOMAIN}:${WEBROOT}/"
  exit 0
else
  bad "$GAGAL verifikasi GAGAL"
  exit 1
fi
