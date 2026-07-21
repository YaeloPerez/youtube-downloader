#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== YT Downloader - Setup ==="
echo

# ── 1. Python ─────────────────────────────────────────────────────────────
echo "[1/4] Verificando Python..."
if ! command -v python3 >/dev/null 2>&1; then
    echo "    Python no encontrado. Instalando con Homebrew..."
    if ! command -v brew >/dev/null 2>&1; then
        echo
        echo "    ERROR: Homebrew no está instalado."
        echo "    Instálalo desde https://brew.sh y vuelve a ejecutar este script."
        exit 1
    fi
    brew install python
else
    echo "    Python ya instalado ($(python3 --version))."
fi

# ── 2. Entorno virtual ───────────────────────────────────────────────────
echo "[2/4] Preparando entorno virtual..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
fi
source venv/bin/activate

# ── 3. Dependencias Python ───────────────────────────────────────────────
echo "[3/4] Instalando dependencias Python..."
pip install -q -r requirements.txt
echo "    Listo."

# ── 4. ffmpeg ─────────────────────────────────────────────────────────────
echo "[4/4] Verificando ffmpeg..."
if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "    ffmpeg no encontrado. Instalando con Homebrew..."
    if command -v brew >/dev/null 2>&1; then
        brew install ffmpeg
    else
        echo
        echo "    ADVERTENCIA: No se pudo instalar ffmpeg automáticamente (falta Homebrew)."
        echo "    Instálalo manualmente: brew install ffmpeg"
        echo "    Sin ffmpeg solo estarán disponibles formatos de baja calidad."
    fi
else
    echo "    ffmpeg ya instalado."
fi

echo
echo "Iniciando YT Downloader en http://localhost:8000"
echo "Presiona Ctrl+C para detener."
echo

uvicorn app:app --host 0.0.0.0 --port 8000 --reload
