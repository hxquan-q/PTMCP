@echo off
cd /d "%~dp0\.."
cd ptmcp-ui-react
call pnpm dev:preview > "%~dp0vite-dev.log" 2>&1
