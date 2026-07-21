# Despliegue en producción — instancia Lightsail existente

Vas a sumar esta app a tu instancia **Ubuntu** (`micro_3_0`, 1GB RAM, 40GB SSD,
IP `35.170.217.121`, us-east-1a) donde ya corren tus proyectos de Node con
pm2. No se crea ninguna instancia nueva ni se toca lo que ya tienes ahí.

## Arquitectura

```
tu-dominio.com  ──HTTPS──▶  nginx (mismo servidor, puerto 443)
                              │  server block NUEVO, solo para este dominio
                              │  proxy_pass a 127.0.0.1:<puerto libre 8010-8030>
                              │  sin buffering (necesario para el streaming
                              │  de progreso por SSE)
                              ▼
                        pm2 → uvicorn (--workers 1)
                              │
                        yt-dlp + ffmpeg
```

- El puerto se elige automáticamente entre 8010-8030 (el script busca el
  primero libre) para no chocar con tus apps de Node.
- **`--workers 1` es obligatorio**: el progreso de las descargas vive en
  memoria del proceso; con más workers, una petición de progreso podría caer
  en otro proceso y fallaría.
- **pm2** administra el proceso igual que tus apps de Node — mismo
  `pm2 status` / `pm2 logs` / `pm2 restart` que ya usas.
- **Sin autenticación** (decisión tomada a propósito): cualquiera que
  descubra la URL podría usar tu servidor para descargar de YouTube. Si en
  algún momento quieres cerrarlo, avísame y agrego un login simple.
- **yt-dlp desde una IP de datacenter**: YouTube a veces limita o bloquea
  descargas desde IPs de AWS ("Sign in to confirm you're not a bot"). Si pasa
  seguido, la solución es pasarle a yt-dlp cookies de una sesión logueada —
  lo vemos si llega a pasar.

## 0. Antes de empezar

Sube los cambios de esta sesión (playlists + config de despliegue) a GitHub:

```bash
git add app.py templates/index.html deploy/ start.sh
git commit -m "Add playlist support and production deployment config"
git push
```

## 0.5 Verificación rápida (una vez, antes de instalar nada)

Confirma que 80/443 ya los tiene nginx, y no algún proceso de Node escuchando
directo en esos puertos (si fuera así, avísame antes de seguir):

```bash
ssh ubuntu@35.170.217.121
sudo ss -tlnp | grep -E ':80 |:443 '
```

Si ves `nginx` en la salida, todo bien, sigue normal. Si ves `node` o `pm2`,
para aquí y lo revisamos juntos antes de instalar nginx.

## 1. DNS

En tu proveedor de DNS, crea un registro **A** apuntando tu dominio/subdominio
a la IP que **ya tienes**: `35.170.217.121`.

| Tipo | Nombre | Valor            |
|------|--------|-------------------|
| A    | yt     | 35.170.217.121    |

(ajusta "yt" al subdominio que prefieras, o usa el dominio raíz)

Espera unos minutos a que propague (`dig yt.tudominio.com` debería devolver
esa IP).

## 2. Instalar la app en el servidor

```bash
ssh ubuntu@35.170.217.121   # con la key que ya usas para esta instancia
```

Ya dentro del servidor:

```bash
git clone https://github.com/YaeloPerez/youtube-downloader.git
cd youtube-downloader
chmod +x deploy/setup_server.sh
./deploy/setup_server.sh https://github.com/YaeloPerez/youtube-downloader.git
```

El script:
- instala python3-venv, ffmpeg, git, nginx y certbot (con `apt install`, no
  rompe nada si ya estaban),
- crea un venv aislado e instala las dependencias de Python,
- busca un puerto libre (8010-8030) para no chocar con tus apps de Node,
- arranca la app con `pm2 start deploy/ecosystem.config.js` + `pm2 save`,
- agrega un **server block nuevo** de nginx solo para este dominio (no toca
  los sitios que ya tienes configurados).

Al final imprime los pasos manuales que faltan (dominio real + certbot).

## 3. Dominio en nginx + HTTPS

```bash
sudo sed -i 's/__DOMAIN__/yt.tudominio.com/' /etc/nginx/sites-available/ytdownloader
sudo nginx -t && sudo systemctl reload nginx    # no afecta tus otros sitios

sudo certbot --nginx -d yt.tudominio.com
```

Certbot agrega el bloque HTTPS, el redirect 80→443, y programa la renovación
automática.

## 4. Probar

Desde el Mac, el Android y el PC, abre `https://yt.tudominio.com` y prueba
tanto un video suelto como una playlist.

## Actualizar la app después de cambios futuros

```bash
ssh ubuntu@35.170.217.121
cd youtube-downloader
git pull
source venv/bin/activate && pip install -q -r requirements.txt && deactivate
pm2 restart ytdownloader
```

## Logs / debug

```bash
pm2 status
pm2 logs ytdownloader
sudo tail -f /var/log/nginx/error.log
```
