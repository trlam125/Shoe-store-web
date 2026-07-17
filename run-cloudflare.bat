@echo off
setlocal
cd /d "%~dp0"

set "CLOUDFLARED=cloudflared.exe"
if exist "%CLOUDFLARED%" goto executable_ready

set "CLOUDFLARED=cloudflared-windows-amd64.exe"
if exist "%CLOUDFLARED%" goto executable_ready

echo [ERROR] Khong tim thay cloudflared.exe trong thu muc project.
echo Tai cloudflared Windows AMD64 va dat file canh run-cloudflare.bat.
exit /b 1

:executable_ready
powershell -NoProfile -Command "try { $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8081/' -TimeoutSec 2; if ($response.StatusCode -eq 200) { exit 0 } } catch {}; exit 1"
if errorlevel 1 (
    echo [WARNING] Website chua phan hoi tai http://localhost:8081/.
    echo Hay Run LshoeStoreApplication truoc khi mo URL Cloudflare.
)

if defined TUNNEL_TOKEN goto named_tunnel

echo [INFO] Dang tao Quick Tunnel cho http://localhost:8081 ...
echo [INFO] URL cong khai se co dang https://...trycloudflare.com
"%CLOUDFLARED%" tunnel --url http://localhost:8081
exit /b %errorlevel%

:named_tunnel
echo [INFO] Dang chay Cloudflare Named Tunnel bang bien TUNNEL_TOKEN ...
"%CLOUDFLARED%" tunnel run
exit /b %errorlevel%
