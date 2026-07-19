@echo off
setlocal
cd /d "%~dp0"
set "PROJECT_ROOT=%CD%"

if defined LSHOE_VENV_DIR (
    for %%I in ("%LSHOE_VENV_DIR%") do set "VENV=%%~fI"
) else (
    set "VENV=%PROJECT_ROOT%\.venv"
)

set "REQUIREMENTS=%~1"
if not defined REQUIREMENTS set "REQUIREMENTS=%PROJECT_ROOT%\requirements.txt"
if not exist "%REQUIREMENTS%" (
    echo [ERROR] Khong tim thay file requirements: "%REQUIREMENTS%"
    exit /b 1
)

if exist "%VENV%\Scripts\python.exe" (
    "%VENV%\Scripts\python.exe" -c "import sys; raise SystemExit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) else 1)" >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Virtualenv "%VENV%" khong dung Python 3.10, 3.11, 3.12 hoac 3.13.
        echo Hay xoa thu muc nay va chay lai setup-venv.bat.
        exit /b 1
    )
    goto install_requirements
)

set "PYTHON_CMD="
where py >nul 2>&1
if not errorlevel 1 (
    py -3.12 --version >nul 2>&1 && set "PYTHON_CMD=py -3.12"
    if not defined PYTHON_CMD py -3.13 --version >nul 2>&1 && set "PYTHON_CMD=py -3.13"
    if not defined PYTHON_CMD py -3.11 --version >nul 2>&1 && set "PYTHON_CMD=py -3.11"
    if not defined PYTHON_CMD py -3.10 --version >nul 2>&1 && set "PYTHON_CMD=py -3.10"
)

if not defined PYTHON_CMD (
    where python >nul 2>&1
    if not errorlevel 1 (
        python -c "import sys; raise SystemExit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) else 1)" >nul 2>&1
        if not errorlevel 1 set "PYTHON_CMD=python"
    )
)

if not defined PYTHON_CMD (
    echo [ERROR] Khong tim thay Python 3.10, 3.11, 3.12 hoac 3.13.
    echo Khuyen nghi cai Python 3.12 hoac dung Python 3.13 da them vao PATH.
    exit /b 1
)

echo [INFO] Tao virtualenv tai "%VENV%"...
%PYTHON_CMD% -m venv "%VENV%"
if errorlevel 1 exit /b 1

:install_requirements
echo [INFO] Virtualenv: "%VENV%"
"%VENV%\Scripts\python.exe" --version
if errorlevel 1 exit /b 1

"%VENV%\Scripts\python.exe" -m pip install -r "%REQUIREMENTS%"
if errorlevel 1 exit /b 1

exit /b 0
