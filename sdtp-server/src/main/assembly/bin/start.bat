@echo off
REM ============================================================================
REM SDT Proxy Startup Script (Windows)
REM
REM Usage:
REM   start.bat              Foreground (double-click to run)
REM   start.bat start         Background
REM   start.bat stop          Stop
REM   start.bat status        Status
REM ============================================================================

setlocal enabledelayedexpansion

REM ---- Config ----
set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "APP_NAME=sdtp"
set "MAIN_CLASS=com.translator.proxy.server.ProxyBootstrap"
set "PID_FILE=%APP_HOME%\%APP_NAME%.pid"
set "LOG_DIR=%APP_HOME%\logs"

REM ---- Env overrides ----
if not defined SDTP_CONFIG set "SDTP_CONFIG=%APP_HOME%\config\proxy-config.yml"
if not defined JAVA_OPTS  set "JAVA_OPTS=-Xms256m -Xmx1024m -XX:+UseG1GC"
set "JAVA_OPTS=%JAVA_OPTS% -Dproxy.config=%SDTP_CONFIG% -Dlog.dir=%LOG_DIR%"

REM ---- Classpath (Java wildcard: lib\* auto-includes all jars) ----
set "CLASSPATH=%APP_HOME%\lib\*;%APP_HOME%\jdbc\*"

REM ---- Ensure dirs ----
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM ---- Find Java ----
set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
"%JAVA_CMD%" -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Set JAVA_HOME or add java to PATH.
    pause
    exit /b 1
)

REM ---- Read PID ----
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
) else (
    set PID=
)

REM ---- Dispatch ----
if "%1"=="stop"   goto :stop
if "%1"=="status" goto :status
if "%1"=="start"  goto :start_bg
goto :start_fg

REM ==================== Foreground ====================
:start_fg
echo [INFO] Starting %APP_NAME% (config: %SDTP_CONFIG%)
echo [INFO] Console + file logging: %LOG_DIR%\proxy.log
echo [INFO] Press Ctrl+C to stop
cd /d "%APP_HOME%"
"%JAVA_CMD%" %JAVA_OPTS% -cp "%CLASSPATH%" %MAIN_CLASS%
goto :end

REM ==================== Background ====================
:start_bg
if defined PID (
    tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
    if !errorlevel! equ 0 (
        echo [WARN] %APP_NAME% is already running (PID: %PID%)
        goto :end
    )
)
echo [INFO] Starting %APP_NAME% in background ...
cd /d "%APP_HOME%"
start "SDT-Proxy" /B "%JAVA_CMD%" %JAVA_OPTS% -cp "%CLASSPATH%" %MAIN_CLASS% ^
    > "%LOG_DIR%\console.log" 2>&1

timeout /t 2 /nobreak >nul
for /f "tokens=2" %%p in ('tasklist /fi "IMAGENAME eq java.exe" /fo table /nh ^| find "%MAIN_CLASS%" 2^>nul') do set PID=%%p
if defined PID (
    echo !PID! > "%PID_FILE%"
    echo [INFO] %APP_NAME% started (PID: !PID!)
    echo [INFO] Log dir: %LOG_DIR%
) else (
    echo [WARN] %APP_NAME% process started but PID unknown
    echo [WARN] Use tasklist to find java processes
)
goto :end

REM ==================== Stop ====================
:stop
if not defined PID (
    echo [WARN] PID file not found, %APP_NAME% may not be running
    goto :end
)
echo [INFO] Stopping %APP_NAME% (PID: %PID%)...
taskkill /PID %PID% /F >nul 2>&1
del "%PID_FILE%" 2>nul
echo [INFO] %APP_NAME% stopped
goto :end

REM ==================== Status ====================
:status
if not defined PID (
    echo [INFO] %APP_NAME% is not running
    goto :end
)
tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
if %errorlevel% equ 0 (
    echo [INFO] %APP_NAME% is running (PID: %PID%)
    echo [INFO] Config: %SDTP_CONFIG%
    echo [INFO] Logs:  %LOG_DIR%
) else (
    echo [INFO] %APP_NAME% is not running (stale PID file)
    del "%PID_FILE%" 2>nul
)
goto :end

:end
endlocal
