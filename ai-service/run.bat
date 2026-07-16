@echo off
setlocal
cd /d "%~dp0"

set "VENV=.venv"
if exist "%VENV%\Scripts\python.exe" goto venv_ready

where py >nul 2>&1
if not errorlevel 1 (
    py -3.11 -m venv "%VENV%"
    goto venv_ready
)

where python >nul 2>&1
if not errorlevel 1 (
    python -m venv "%VENV%"
    goto venv_ready
)

echo [ERROR] Khong tim thay Python. Hay cai Python 3.11 va chon Add Python to PATH.
exit /b 1

:venv_ready
"%VENV%\Scripts\python.exe" --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Virtualenv bi hong. Hay xoa thu muc ai-service\.venv va chay lai.
    exit /b 1
)

"%VENV%\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 exit /b 1

"%VENV%\Scripts\python.exe" run.py
