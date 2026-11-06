@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0platform.ps1" -Action Status %*
