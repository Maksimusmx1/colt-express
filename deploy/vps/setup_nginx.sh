#!/usr/bin/env bash
#
# Установка nginx с самоподписанным сертификатом для Colt Express.
# Запускается на VPS: sudo bash /tmp/colt-deploy/setup_nginx.sh
#
set -euo pipefail

DOMAIN="${1:-212.113.99.178}"

echo "==> Installing nginx..."
apt-get update -y -q
apt-get install -y -q nginx

echo "==> Generating self-signed certificate for $DOMAIN..."
mkdir -p /etc/nginx/ssl
openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
    -keyout /etc/nginx/ssl/colt.key \
    -out /etc/nginx/ssl/colt.crt \
    -subj "/CN=$DOMAIN/O=ColtExpress/C=RU"

echo "==> Configuring nginx..."
cat > /etc/nginx/sites-available/colt <<'NGINX'
server {
    listen 8443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/ssl/colt.crt;
    ssl_certificate_key /etc/nginx/ssl/colt.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}

server {
    listen 8088;
    server_name _;
    return 301 https://$host:8443$request_uri;
}
NGINX

ln -sf /etc/nginx/sites-available/colt /etc/nginx/sites-enabled/colt
rm -f /etc/nginx/sites-enabled/default

echo "==> Testing nginx config..."
nginx -t

echo "==> Starting nginx..."
systemctl enable nginx
systemctl restart nginx

echo ""
echo "Nginx configured with self-signed certificate."
echo "HTTPS: https://$DOMAIN"
echo "WSS:   wss://$DOMAIN/ws"
