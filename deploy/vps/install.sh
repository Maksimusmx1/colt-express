#!/usr/bin/env bash
#
# Установка сервера Colt Express на VPS (Debian/Ubuntu).
# Запускается upload.ps1 автоматически; можно запустить и вручную:
#   sudo bash /tmp/colt-deploy/install.sh
#
set -euo pipefail

DEPLOY_DIR=/tmp/colt-deploy
INSTALL_ROOT=/opt/colt
SERVER_DIR="$INSTALL_ROOT/server"
APK_DIR="$INSTALL_ROOT/apk"
WEB_DIR="$INSTALL_ROOT/web"
LOG_DIR=/var/log/colt
SERVICE_NAME=colt-express

# ---------------------------------------------------------------- Java 17
echo "==> Checking Java (need >= 17)..."
JAVA_OK=0
if command -v java >/dev/null 2>&1; then
  VER=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | sed 's/^1\.//; s/\..*//')
  if [ "${VER:-0}" -ge 17 ]; then JAVA_OK=1; fi
fi
if [ "$JAVA_OK" -ne 1 ]; then
  echo "==> Installing OpenJDK 17 JRE (headless)..."
  sudo apt-get update -y -q
  sudo apt-get install -y -q openjdk-17-jre-headless
fi

# ---------------------------------------------------------------- user
echo "==> Creating system user 'colt'..."
if ! id -u colt >/dev/null 2>&1; then
  sudo useradd --system --home-dir "$INSTALL_ROOT" --shell /usr/sbin/nologin colt
fi

# ---------------------------------------------------------------- files
echo "==> Installing files..."
sudo install -d "$SERVER_DIR" "$APK_DIR" "$WEB_DIR" "$LOG_DIR"
sudo tar -xzf "$DEPLOY_DIR/colt-express-server.tar.gz" -C "$SERVER_DIR" --strip-components=1
sudo install -m 644 "$DEPLOY_DIR/app-debug.apk" "$APK_DIR/app-debug.apk"
if [ -d "$DEPLOY_DIR/web" ]; then
  sudo cp -rf "$DEPLOY_DIR/web/"* "$WEB_DIR/"
fi
sudo install -m 644 "$DEPLOY_DIR/colt-express.service" "/etc/systemd/system/$SERVICE_NAME.service"

echo "==> Setting ownership..."
sudo chown -R colt:colt "$INSTALL_ROOT" "$LOG_DIR"

# ---------------------------------------------------------------- systemd
echo "==> Starting service..."
sudo systemctl daemon-reload
sudo systemctl enable --now "$SERVICE_NAME"
sleep 2
sudo systemctl status "$SERVICE_NAME" --no-pager || true

# ---------------------------------------------------------------- firewall
echo "==> Opening port 8080 in ufw (if active)..."
sudo ufw allow 8080/tcp >/dev/null 2>&1 || true

# ---------------------------------------------------------------- done
echo ""
VPS_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo "Done." 
echo "  Health check : curl http://localhost:8080/health"
echo "  Logs         : journalctl -u $SERVICE_NAME -f"
echo "  Client addr  : ${VPS_IP:-<VPS_IP>}   (вводится в приложении без порта-схемы: 'IP:8080')"
