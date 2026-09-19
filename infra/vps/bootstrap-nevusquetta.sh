#!/usr/bin/env bash
set -Eeuo pipefail

DOMAIN="${NEVUS_DOMAIN:-nevusquetta.tech}"
DEPLOY_USER="${NEVUS_DEPLOY_USER:-nevusdeploy}"
ROOT_DIR="/srv/nevusquetta"
SITE_DIR="$ROOT_DIR/site"
RELEASES_DIR="$ROOT_DIR/releases"
TOOLS_DIR="$ROOT_DIR/tools"
CURRENT_LINK="$ROOT_DIR/current"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Jalankan bootstrap ini sebagai root." >&2
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "Bootstrap saat ini mendukung VPS Debian/Ubuntu berbasis apt." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends   nginx   rsync   unzip   curl   ca-certificates   certbot   python3-certbot-nginx

if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi

install -d -m 0755 "$ROOT_DIR" "$SITE_DIR" "$RELEASES_DIR" "$TOOLS_DIR"
install -d -m 0700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
touch "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 0600 "/home/$DEPLOY_USER/.ssh/authorized_keys"

chown -R "$DEPLOY_USER:$DEPLOY_USER" "$RELEASES_DIR"
chown "$DEPLOY_USER:$DEPLOY_USER" "$SITE_DIR"

for tool in deploy-candidate.sh deploy-release.sh; do
  if [[ -f "$SCRIPT_DIR/$tool" ]]; then
    install -m 0755 -o "$DEPLOY_USER" -g "$DEPLOY_USER"       "$SCRIPT_DIR/$tool" "$TOOLS_DIR/$tool"
  fi
done

cat > "$SITE_DIR/index.html" <<EOF
<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>NevusQuetta</title>
</head>
<body>
  <main>
    <h1>NevusQuetta</h1>
    <p>Native Android browser release service.</p>
    <p><a href="/download/">Download build terbaru</a></p>
  </main>
</body>
</html>
EOF

cat > "/etc/nginx/sites-available/$DOMAIN" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN www.$DOMAIN;

    root $SITE_DIR;
    index index.html;

    location = /healthz {
        default_type text/plain;
        return 200 "ok\n";
    }

    location / {
        try_files \$uri \$uri/ =404;
    }

    location /download/ {
        alias $CURRENT_LINK/;
        autoindex on;
        add_header X-Content-Type-Options nosniff always;
        add_header Referrer-Policy no-referrer always;
        add_header Cache-Control "no-store" always;
    }

    location ~* \.(?:apk|zip)$ {
        add_header X-Content-Type-Options nosniff always;
        add_header Content-Disposition attachment always;
    }

    server_tokens off;
}
EOF

ln -sfn "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
rm -f /etc/nginx/sites-enabled/default

nginx -t
systemctl enable nginx
systemctl restart nginx

if command -v ufw >/dev/null 2>&1; then
  ufw allow OpenSSH || true
  ufw allow 'Nginx Full' || true
fi

echo
echo "BOOTSTRAP=PASS"
echo "DOMAIN=$DOMAIN"
echo "DEPLOY_USER=$DEPLOY_USER"
echo "NEXT:"
echo "1. Tambahkan public SSH key GitHub deployment ke /home/$DEPLOY_USER/.ssh/authorized_keys"
echo "2. Arahkan DNS A $DOMAIN dan www.$DOMAIN ke VPS."
echo "3. Setelah DNS resolve, jalankan infra/vps/enable-tls.sh sebagai root."
