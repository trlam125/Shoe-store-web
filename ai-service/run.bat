@echo off
setlocal
set "SERVICE_DIR=%~dp0"
for %%I in ("%SERVICE_DIR%..") do set "PROJECT_ROOT=%%~fI"

if defined LSHOE_VENV_DIR (
    for %%I in ("%LSHOE_VENV_DIR%") do set "VENV=%%~fI"
) else (
    set "VENV=%PROJECT_ROOT%\.venv"
)

if /I not "%LSHOE_SKIP_VENV_SETUP%"=="true" (
    call "%PROJECT_ROOT%\setup-venv.bat" "%SERVICE_DIR%requirements.txt"
    if errorlevel 1 exit /b 1
)

if not exist "%VENV%\Scripts\python.exe" (
    echo [ERROR] Khong tim thay Python trong virtualenv: "%VENV%"
    echo Hay chay setup-venv.bat tai thu muc goc.
    exit /b 1
)

cd /d "%SERVICE_DIR%"
"%VENV%\Scripts\python.exe" run.py
exit /b %errorlevel%
