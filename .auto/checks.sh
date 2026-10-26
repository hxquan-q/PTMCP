#!/bin/bash
# 正确性门禁：前端 lint/format/typecheck/build + 仓库文档/流程检查。成功时静默。
set -uo pipefail
cd "$(dirname "$0")/.."

if ! pnpm --dir ptmcp-ui check > /tmp/ptmcp-ui-check.log 2>&1; then
  echo "pnpm check FAILED:"
  tail -30 /tmp/ptmcp-ui-check.log
  exit 1
fi

if ! python3 scripts/check.py > /tmp/ptmcp-repo-check.log 2>&1; then
  echo "repo check FAILED:"
  tail -30 /tmp/ptmcp-repo-check.log
  exit 1
fi

echo "checks OK"
