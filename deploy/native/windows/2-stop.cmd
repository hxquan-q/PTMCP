@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0platform.ps1" -Action Stop %*
if errorlevel 1 pause
