@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Locate cloudflared in the project directory or in PATH.
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

rem Prefer SERVER_PORT from the current environment. If it is not set,
rem read it from .env. Fall back to the Spring Boot default used by this project.
if not defined SERVER_PORT if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
        if /i "%%A"=="SERVER_PORT" set "SERVER_PORT=%%B"
    )
)
if not defined SERVER_PORT set "SERVER_PORT=8081"
set "SERVER_PORT=%SERVER_PORT:"=%"
set "LOCAL_URL=http://127.0.0.1:%SERVER_PORT%"

rem A stale Quick Tunnel URL must not be reused after cloudflared has stopped.
if exist ".runtime\cloudflare-public-url.txt" del /q ".runtime\cloudflare-public-url.txt" >nul 2>&1
if exist ".runtime\cloudflare-public-url.txt.tmp" del /q ".runtime\cloudflare-public-url.txt.tmp" >nul 2>&1

rem Check that the local Spring Boot application is reachable.
where curl.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Khong tim thay curl.exe de kiem tra website cuc bo.
    echo Windows 10/11 thuong da co san curl.exe trong PATH.
    pause
    exit /b 1
)

set "HTTP_STATUS="
for /f "delims=" %%S in ('curl.exe --silent --output NUL --write-out "%%{http_code}" --connect-timeout 3 --max-time 5 "%LOCAL_URL%/" 2^>nul') do set "HTTP_STATUS=%%S"
echo(%HTTP_STATUS%| %SystemRoot%\System32\findstr.exe /r /x "[1-4][0-9][0-9]" >nul
if errorlevel 1 (
    echo [ERROR] Website chua san sang tai %LOCAL_URL%/
    echo Hay chay LshoeStoreApplication truoc va kiem tra SERVER_PORT.
    pause
    exit /b 1
)

echo [INFO] Website dang chay tai %LOCAL_URL%/ ^(HTTP %HTTP_STATUS%^).

rem Keep compatibility with either token variable name.
if not defined TUNNEL_TOKEN if defined CLOUDFLARE_TUNNEL_TOKEN set "TUNNEL_TOKEN=%CLOUDFLARE_TUNNEL_TOKEN%"

if defined TUNNEL_TOKEN goto RUN_NAMED_TUNNEL

call :RUN_QUICK_TUNNEL
exit /b %errorlevel%

:RUN_NAMED_TUNNEL
echo [INFO] Dang chay Cloudflare Named Tunnel...
"%CLOUDFLARED%" tunnel run --token "%TUNNEL_TOKEN%"
exit /b %errorlevel%

:RUN_QUICK_TUNNEL
setlocal EnableDelayedExpansion

if not exist ".runtime" mkdir ".runtime" >nul 2>&1
set "PUBLIC_URL="

echo [INFO] Dang tao Quick Tunnel cho %LOCAL_URL% ...
echo [INFO] URL trycloudflare se duoc ghi vao .runtime\cloudflare-public-url.txt.
echo [INFO] Nhan Ctrl+C de dung tunnel.

rem Read cloudflared output line by line, display it, and capture the public URL.
for /f "usebackq delims=" %%L in (`"%CLOUDFLARED%" tunnel --url "%LOCAL_URL%" 2^>^&1`) do (
    set "LINE=%%L"
    echo(!LINE!

    if not defined PUBLIC_URL (
        for %%T in (!LINE!) do (
            set "TOKEN=%%~T"
            if /i "!TOKEN:~0,8!"=="https://" (
                set "WITHOUT_DOMAIN=!TOKEN:.trycloudflare.com=!"
                if /i not "!WITHOUT_DOMAIN!"=="!TOKEN!" (
                    set "PUBLIC_URL=!TOKEN!"
                    if "!PUBLIC_URL:~-1!"=="/" set "PUBLIC_URL=!PUBLIC_URL:~0,-1!"
                    >".runtime\cloudflare-public-url.txt.tmp" echo(!PUBLIC_URL!
                    move /y ".runtime\cloudflare-public-url.txt.tmp" ".runtime\cloudflare-public-url.txt" >nul
                    echo [INFO] Cloudflare public URL: !PUBLIC_URL!
                )
            )
        )
    )
)

set "TUNNEL_EXIT_CODE=!errorlevel!"
if exist ".runtime\cloudflare-public-url.txt" del /q ".runtime\cloudflare-public-url.txt" >nul 2>&1
if exist ".runtime\cloudflare-public-url.txt.tmp" del /q ".runtime\cloudflare-public-url.txt.tmp" >nul 2>&1

endlocal & exit /b %TUNNEL_EXIT_CODE%
