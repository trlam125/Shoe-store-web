@echo off
setlocal
cd /d "%~dp0"

set "CLOUDFLARED="
if exist "cloudflared.exe" set "CLOUDFLARED=cloudflared.exe"
if not defined CLOUDFLARED if exist "cloudflared-windows-amd64.exe" set "CLOUDFLARED=cloudflared-windows-amd64.exe"
if not defined CLOUDFLARED (
    where cloudflared >nul 2>&1
    if not errorlevel 1 set "CLOUDFLARED=cloudflared"
)

if not defined CLOUDFLARED (
    echo [ERROR] Khong tim thay cloudflared.exe trong project hoac PATH.
    pause
    exit /b 1
)

powershell -NoProfile -Command "try { $r=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8081/' -TimeoutSec 3; if ($r.StatusCode -lt 500) { exit 0 } } catch {}; exit 1"
if errorlevel 1 (
    echo [ERROR] Website chua chay tai http://127.0.0.1:8081/
    echo Hay chay LshoeStoreApplication truoc.
    pause
    exit /b 1
)

if defined TUNNEL_TOKEN (
    echo [INFO] Dang chay Cloudflare Named Tunnel...
    "%CLOUDFLARED%" tunnel run --token "%TUNNEL_TOKEN%"
) else (
    echo [INFO] Dang tao Quick Tunnel cho http://127.0.0.1:8081 ...
    echo [INFO] Hay dat APP_PUBLIC_BASE_URL theo URL trycloudflare neu can gui link reset mat khau.
    "%CLOUDFLARED%" tunnel --url http://127.0.0.1:8081
)
exit /b %errorlevel%
