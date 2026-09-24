#!/usr/bin/env bash
set -Eeuo pipefail

DOMAIN="${NEVUS_DOMAIN:-nevusquetta.tech}"
EMAIL="${NEVUS_TLS_EMAIL:-}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan sebagai root." >&2
  exit 1
fi

if [[ -z "$EMAIL" ]]; then
  echo "Set NEVUS_TLS_EMAIL ke email admin sertifikat." >&2
  exit 1
fi

nginx -t
systemctl reload nginx

certbot --nginx   --non-interactive   --agree-tos   --redirect   --email "$EMAIL"   -d "$DOMAIN"   -d "www.$DOMAIN"

nginx -t
systemctl reload nginx
systemctl enable certbot.timer >/dev/null 2>&1 || true

curl --fail --silent --show-error "https://$DOMAIN/healthz"
echo
echo "TLS=PASS"
