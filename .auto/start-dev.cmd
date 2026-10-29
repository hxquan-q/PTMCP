@echo off
cd /d "%~dp0\.."
cd ptmcp-ui-react
node node_modules\vite\bin\vite.js --mode frontend-preview --host 127.0.0.1 --port 5175 --strictPort > "%~dp0vite-dev.log" 2>&1
