#!/bin/bash
# Run this ON your existing Lightsail "Ubuntu" instance (via SSH), not on
# your Mac. Adds this app alongside your Node/pm2 apps without touching them.
# Usage: ./setup_server.sh <git-repo-url>
set -e

REPO_URL="${1:?Uso: ./setup_server.sh <url-de-tu-repo-git>}"
APP_DIR="/home/ubuntu/youtube-downloader"
PORT_RANGE_START=8010
PORT_RANGE_END=8030

echo "== Instalando dependencias del sistema (no toca tus apps de Node) =="
sudo apt update
sudo apt install -y python3-venv python3-pip ffmpeg git nginx certbot python3-certbot-nginx

if [ ! -d "$APP_DIR" ]; then
  git clone "$REPO_URL" "$APP_DIR"
fi
cd "$APP_DIR"

echo "== Entorno virtual + dependencias Python (aislado, no choca con node_modules) =="
python3 -m venv venv
source venv/bin/activate
pip install -q -r requirements.txt
deactivate

echo "== Buscando un puerto libre para no chocar con tus apps de Node =="
PORT=""
for p in $(seq $PORT_RANGE_START $PORT_RANGE_END); do
  if ! ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":${p}\$"; then
    PORT=$p
    break
  fi
done
if [ -z "$PORT" ]; then
  echo "No se encontró puerto libre entre $PORT_RANGE_START-$PORT_RANGE_END" >&2
  exit 1
fi
echo "Puerto elegido: $PORT"
sed -i "s/8010/$PORT/" deploy/ecosystem.config.js

echo "== Arrancando con pm2 =="
pm2 start deploy/ecosystem.config.js
pm2 save

echo "== Config de nginx (sitio nuevo — no toca los que ya tienes) =="
sed -e "s/__PORT__/$PORT/" deploy/nginx.conf.template | sudo tee /etc/nginx/sites-available/ytdownloader > /dev/null
sudo ln -sf /etc/nginx/sites-available/ytdownloader /etc/nginx/sites-enabled/ytdownloader

cat <<EOF

== Siguientes pasos manuales ==
1. Pon tu dominio real en la config de nginx:
     sudo sed -i 's/__DOMAIN__/yt.tudominio.com/' /etc/nginx/sites-available/ytdownloader
2. Verifica y recarga nginx (no afecta tus otros sitios):
     sudo nginx -t && sudo systemctl reload nginx
3. Certificado HTTPS (requiere que el DNS ya apunte a esta IP):
     sudo certbot --nginx -d yt.tudominio.com
4. Verifica que corre:
     pm2 status
     pm2 logs ytdownloader

App corriendo en 127.0.0.1:$PORT (no expuesto directo a internet, solo vía nginx).
EOF
