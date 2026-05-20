@echo off
cd /d "%~dp0"

echo === YT Downloader - Setup ===
echo.

:: Instalar dependencias Python
echo [1/2] Instalando dependencias Python...
pip install -r requirements.txt -q
echo     Listo.

:: Verificar ffmpeg
echo [2/2] Verificando ffmpeg...
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
        :: Recargar PATH para que uvicorn lo encuentre
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
