@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-prereq.ps1" %*
if errorlevel 1 pause
