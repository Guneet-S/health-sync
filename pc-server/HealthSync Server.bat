@echo off
title HealthSync Server v2.1
color 0A
cls

echo.
echo  ==========================================
echo   HealthSync Server v2.1  ^|  SQLite Backend
echo  ==========================================
echo.

:: Create required folders if missing
if not exist "C:\claude-agents\health-sync\pc-server\logs" mkdir "C:\claude-agents\health-sync\pc-server\logs"
if not exist "C:\claude-agents\health-sync\pc-server\uploads" mkdir "C:\claude-agents\health-sync\pc-server\uploads"
if not exist "C:\claude-agents\health-sync\pc-server\exports" mkdir "C:\claude-agents\health-sync\pc-server\exports"

:: Check dependencies
echo  Checking dependencies...
C:\Python314\python.exe -c "import flask, sqlalchemy, flask_sqlalchemy" 2>nul
if errorlevel 1 (
    echo  Installing missing packages...
    C:\Python314\python.exe -m pip install flask sqlalchemy flask-sqlalchemy --quiet
)

echo  Dependencies OK
echo.
echo  Starting server on 0.0.0.0:5000
echo  DB: C:\claude-agents\health-sync\pc-server\health.db
echo  Logs: C:\claude-agents\health-sync\pc-server\logs\server.log
echo.
echo  Press Ctrl+C to stop
echo  ==========================================
echo.

C:\Python314\python.exe "C:\claude-agents\health-sync\pc-server\app.py"

echo.
echo  ==========================================
echo   Server stopped. Check error above.
echo  ==========================================
echo.
pause
