@echo off
cd /d "%~dp0"

echo === YT Downloader - Setup ===
echo.

:: ── 1. Python ─────────────────────────────────────────────────────────────────
echo [1/3] Verificando Python...
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo     Python no encontrado. Instalando con winget...
    winget install --id Python.Python.3 -e --accept-package-agreements --accept-source-agreements
    if %errorlevel% neq 0 (
        echo.
        echo     ERROR: No se pudo instalar Python automaticamente.
        echo     Descargalo manualmente desde https://python.org
        echo     y asegurate de marcar "Add Python to PATH".
        pause
        exit /b 1
    )
    echo     Python instalado correctamente.
    :: Recargar PATH
    for /f "tokens=*" %%i in ('powershell -Command "[System.Environment]::GetEnvironmentVariable(\"PATH\",\"User\")"') do set "PATH=%%i;%PATH%"
) else (
    echo     Python ya instalado.
)

:: ── 2. Dependencias Python ────────────────────────────────────────────────────
echo [2/3] Instalando dependencias Python...
pip install -r requirements.txt -q
echo     Listo.

:: ── 3. ffmpeg ─────────────────────────────────────────────────────────────────
echo [3/3] Verificando ffmpeg...
where ffmpeg >nul 2>&1
if %errorlevel% neq 0 (
    echo     ffmpeg no encontrado. Instalando con winget...
    winget install --id Gyan.FFmpeg -e --accept-package-agreements --accept-source-agreements
    if %errorlevel% neq 0 (
        echo.
        echo     ADVERTENCIA: No se pudo instalar ffmpeg automaticamente.
        echo     Instálalo manualmente: winget install ffmpeg
        echo     Sin ffmpeg solo estarán disponibles formatos de baja calidad.
        echo.
    ) else (
        echo     ffmpeg instalado correctamente.
        for /f "tokens=*" %%i in ('powershell -Command "[System.Environment]::GetEnvironmentVariable(\"PATH\",\"User\")"') do set "PATH=%%i;%PATH%"
    )
) else (
    echo     ffmpeg ya instalado.
)

echo.
echo Iniciando YT Downloader en http://localhost:8000
echo Presiona Ctrl+C para detener.
echo.

uvicorn app:app --host 0.0.0.0 --port 8000 --reload
pause
