@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp00-install.ps1" %*
if errorlevel 1 pause
