@echo off
cd /d "%~dp0"
call venv\Scripts\activate.bat
pip install fastapi uvicorn --quiet
uvicorn api:app --host 0.0.0.0 --port 8000 --reload
