#!/bin/bash
# PTMCP 联调真实化基准：React 契约测试 + 后端测试（本机即 PTMCP-Dev WSL：Java21/Maven3.9.9/Docker/Testcontainers）。
# 输出 METRIC 行；后端日志尾部存 .auto/last-be.log。
# 环境开关：PTMCP_SKIP_BE=1 只跑前端（快速迭代）；默认跑全量。
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

RE_PASS=0; RE_FAIL=0; BE_PASS=0; BE_FAIL=0

# --- Node 定位（本仓 node 为 Windows exe，需 Windows 路径） ---
NODE_BIN=""
for cand in "$(command -v node 2>/dev/null || true)" \
            "$HOME/bin/node" \
            "/mnt/c/Users/xquan/.openforge/runtimes/node/22.22.2/node.exe"; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then NODE_BIN="$cand"; break; fi
done

# --- React 前端契约测试（ptmcp-ui-react/tests） ---
if [ -n "$NODE_BIN" ]; then
  re_out=$(cd ptmcp-ui-react && "$NODE_BIN" --experimental-strip-types --test tests/*.test.ts 2>&1 || true)
  RE_PASS=$(printf '%s' "$re_out" | sed -n 's/^# pass \([0-9]*\)$/\1/p' | tail -1)
  RE_FAIL=$(printf '%s' "$re_out" | sed -n 's/^# fail \([0-9]*\)$/\1/p' | tail -1)
  if [ -z "${RE_PASS:-}" ]; then
    RE_PASS=$(printf '%s' "$re_out" | sed -n 's/^ℹ pass \([0-9]*\)$/\1/p' | tail -1)
    RE_FAIL=$(printf '%s' "$re_out" | sed -n 's/^ℹ fail \([0-9]*\)$/\1/p' | tail -1)
  fi
else
  echo "REACT_TESTS re_pass=0 re_fail=0 node_missing"
fi
RE_PASS=${RE_PASS:-0}; RE_FAIL=${RE_FAIL:-0}
echo "REACT_TESTS re_pass=$RE_PASS re_fail=$RE_FAIL"

# --- 后端测试（本机 PTMCP-Dev；需 Docker） ---
if [ "${PTMCP_SKIP_BE:-0}" = "1" ]; then
  echo "BE_TESTS be_pass=0 be_fail=0 mvn_exit=skipped_by_env"
elif docker info > /dev/null 2>&1; then
  be_log=.auto/last-be.log
  ( cd ptmcp-parent && \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    exec /mnt/d/config/apache-maven-3.9.9/bin/mvn -o -B -ntp \
      -Dmaven.repo.local=/mnt/d/config/ptmcp/cache/maven \
      -pl ptmcp-admin,ptmcp-authz,ptmcp-gateway -am test
  ) > "$be_log" 2>&1
  BE_EXIT=$?
  tail -20 "$be_log" | sed 's/^/BE | /'
  summary=$(awk '/Results:/{f=1;next} f && /Tests run:/{print; f=0}' "$be_log")
  BE_RUN=$(printf '%s' "$summary" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
  BE_FL=$(printf '%s' "$summary" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
  BE_ER=$(printf '%s' "$summary" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
  BE_PASS=$((BE_RUN - BE_FL - BE_ER)); BE_FAIL=$((BE_FL + BE_ER))
  echo "BE_TESTS be_pass=$BE_PASS be_fail=$BE_FAIL mvn_exit=$BE_EXIT"
else
  echo "BE_TESTS be_pass=0 be_fail=0 mvn_exit=skipped_no_docker"
fi

echo "METRIC tests_passing=$((RE_PASS + BE_PASS))"
echo "METRIC tests_failed=$((RE_FAIL + BE_FAIL))"
echo "METRIC react_tests_pass=$RE_PASS"
echo "METRIC be_tests_pass=$BE_PASS"
