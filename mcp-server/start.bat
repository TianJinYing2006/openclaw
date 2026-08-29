@echo off
echo ============================================
echo  WeChatBot MCP Server
echo ============================================

cd /d "%~dp0"

REM Check Python
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo [Error] Python not found. Please install Python 3.10+
    echo Download: https://www.python.org/downloads/
    pause
    exit /b 1
)

REM Check .env file
if not exist ".env" (
    echo [Info] .env not found, creating from template...
    copy .env.example .env >nul
    echo [Info] .env created. Edit it to fill in your API Key.
    echo.
)

REM Check venv
if not exist ".venv" (
    echo [Init] Creating virtual environment...
    python -m venv .venv
    if %errorlevel% neq 0 (
        echo [Warn] venv creation failed, using system Python instead.
        echo.
        goto :install_deps
    )
)

REM Activate venv
call .venv\Scripts\activate.bat

:install_deps
echo [Init] Installing dependencies...
pip install -r requirements.txt -q

echo.
echo [Start] MCP Server starting...
echo [Start] Listening on http://localhost:8090
echo [Start] Press Ctrl+C to stop
echo.

python server.py

pause
