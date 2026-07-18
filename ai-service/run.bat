@echo off
setlocal
cd /d "%~dp0"

set "VENV=.venv"
if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" --version >nul 2>&1
    if not errorlevel 1 goto venv_ready
)
if exist "venv\Scripts\python.exe" (
    "venv\Scripts\python.exe" --version >nul 2>&1
    if not errorlevel 1 (
        set "VENV=venv"
        goto venv_ready
    )
)

where py >nul 2>&1
if not errorlevel 1 (
    py -3.11 --version >nul 2>&1
    if not errorlevel 1 (
        py -3.11 -m venv "%VENV%"
        if errorlevel 1 exit /b 1
        goto venv_ready
    )
    py -3.12 --version >nul 2>&1
    if not errorlevel 1 (
        py -3.12 -m venv "%VENV%"
        if errorlevel 1 exit /b 1
        goto venv_ready
    )
    py -3 --version >nul 2>&1
    if not errorlevel 1 (
        py -3 -m venv "%VENV%"
        if errorlevel 1 exit /b 1
        goto venv_ready
    )
)

where python >nul 2>&1
if not errorlevel 1 (
    python -m venv "%VENV%"
    if errorlevel 1 exit /b 1
    goto venv_ready
)

echo [ERROR] Khong tim thay Python. Hay cai Python 3.11 hoac 3.12 va chon Add Python to PATH.
exit /b 1

:venv_ready
"%VENV%\Scripts\python.exe" --version
if errorlevel 1 (
    echo [ERROR] Khong the khoi dong Python trong virtualenv "%VENV%".
    exit /b 1
)

"%VENV%\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 exit /b 1

"%VENV%\Scripts\python.exe" run.py
