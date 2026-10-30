#!/bin/bash
# PTMCP 联调真实化基准：前端契约测试（Vue + React）+ 后端测试（需 Docker，无 Docker 时跳过并标记）。
# 输出 METRIC 行；后端日志尾部存 .auto/last-be.log。
set -uo pipefail
cd "$(dirname "$0")/.."

FE_PASS=0; FE_FAIL=0; RE_PASS=0; RE_FAIL=0; BE_PASS=0; BE_FAIL=0


# --- React 前端契约测试（ptmcp-ui-react/tests） ---
re_out=$(cd ptmcp-ui-react && pnpm exec node --experimental-strip-types --test tests/*.test.ts 2>&1 || true)
RE_PASS=$(printf '%s' "$re_out" | sed -n 's/^ℹ pass \([0-9]*\)$/\1/p' | tail -1)
RE_FAIL=$(printf '%s' "$re_out" | sed -n 's/^ℹ fail \([0-9]*\)$/\1/p' | tail -1)
RE_PASS=${RE_PASS:-0}; RE_FAIL=${RE_FAIL:-0}
echo "REACT_TESTS re_pass=$RE_PASS re_fail=$RE_FAIL"

# --- 后端测试（Testcontainers，需 Docker） ---
if docker info > /dev/null 2>&1; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  be_log=.auto/last-be.log
  (cd ptmcp-parent && mvn -B -ntp '-Dptmcp.test.pgImage=postgres:15' -pl ptmcp-admin,ptmcp-gateway -am test) > "$be_log" 2>&1
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
