@echo off
cd /d "%~dp0"

echo Instalando dependencias...
pip install -r requirements.txt -q

echo.
echo Iniciando YT Downloader en http://localhost:8000
echo Presiona Ctrl+C para detener.
echo.

uvicorn app:app --host 0.0.0.0 --port 8000 --reload
pause
