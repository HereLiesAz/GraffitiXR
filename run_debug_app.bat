@echo off
powershell -ExecutionPolicy Bypass -File ".\run_debug_monitor.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Script failed with error level %ERRORLEVEL%.
    pause
)
