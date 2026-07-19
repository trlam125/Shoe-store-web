@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "NGROK_CMD="
if exist "%~dp0ngrok.exe" set "NGROK_CMD=%~dp0ngrok.exe"
if not defined NGROK_CMD (
    where ngrok.exe >nul 2>&1
    if not errorlevel 1 set "NGROK_CMD=ngrok.exe"
)

if not defined NGROK_CMD (
    echo [ERROR] Khong tim thay ngrok.exe.
    echo Cai ngrok theo trang dashboard, hoac dat ngrok.exe trong thu muc project.
    pause
    exit /b 1
)

set "LOCAL_PORT=8081"
set "PUBLIC_URL="
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /C:"SERVER_PORT=" /C:"NGROK_PUBLIC_URL=" ".env"`) do (
        if /I "%%A"=="SERVER_PORT" set "LOCAL_PORT=%%B"
        if /I "%%A"=="NGROK_PUBLIC_URL" set "PUBLIC_URL=%%B"
    )
)

echo [INFO] Ngrok se chuyen tiep den http://127.0.0.1:%LOCAL_PORT%
if defined PUBLIC_URL (
    echo [INFO] Public URL: %PUBLIC_URL%
    "%NGROK_CMD%" http 127.0.0.1:%LOCAL_PORT% --url "%PUBLIC_URL%" --inspect=false
) else (
    echo [WARN] NGROK_PUBLIC_URL dang trong. Ngrok se dung endpoint gan cho tai khoan.
    "%NGROK_CMD%" http 127.0.0.1:%LOCAL_PORT% --inspect=false
)

pause
