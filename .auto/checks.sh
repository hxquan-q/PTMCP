#!/bin/bash
# 正确性门禁：前端 lint/format/typecheck/build（直连 node，等价 pnpm check；pnpm 在本机被
# esbuild approve-builds 策略阻断）+ 仓库文档/流程检查。成功时静默。
set -uo pipefail
cd "$(dirname "$0")/.."

NODE_BIN="$(command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ]; then
  NODE_BIN="/mnt/c/Users/xquan/.openforge/runtimes/node/22.22.2/node.exe"
fi

cd ptmcp-ui-react || exit 1
"$NODE_BIN" node_modules/eslint/bin/eslint.js src --max-warnings 0 > /tmp/ptmcp-ui-react-check.log 2>&1 || { echo "eslint FAILED:"; tail -30 /tmp/ptmcp-ui-react-check.log; exit 1; }
"$NODE_BIN" node_modules/prettier/bin/prettier.cjs --check src tests >> /tmp/ptmcp-ui-react-check.log 2>&1 || { echo "prettier FAILED:"; tail -30 /tmp/ptmcp-ui-react-check.log; exit 1; }
"$NODE_BIN" node_modules/typescript/bin/tsc --noEmit >> /tmp/ptmcp-ui-react-check.log 2>&1 || { echo "tsc FAILED:"; tail -30 /tmp/ptmcp-ui-react-check.log; exit 1; }
"$NODE_BIN" node_modules/vite/bin/vite.js build >> /tmp/ptmcp-ui-react-check.log 2>&1 || { echo "vite build FAILED:"; tail -30 /tmp/ptmcp-ui-react-check.log; exit 1; }
cd ..

if ! python3 scripts/check.py > /tmp/ptmcp-repo-check.log 2>&1; then
  echo "repo check FAILED:"
  tail -30 /tmp/ptmcp-repo-check.log
  exit 1
fi
