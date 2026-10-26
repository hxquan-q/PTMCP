#!/bin/bash
# PTMCP 联调真实化基准：前端契约测试 + 后端测试（admin 及依赖模块）。
# 输出 METRIC 行；后端日志尾部存 .auto/last-be.log。
set -uo pipefail
cd "$(dirname "$0")/.."

FE_PASS=0; FE_FAIL=0; BE_PASS=0; BE_FAIL=0

# --- 前端契约测试（快，无构建） ---
fe_out=$(cd ptmcp-ui && node --experimental-strip-types --test tests/*.test.ts tests/*.test.mjs 2>&1 || true)
FE_PASS=$(printf '%s' "$fe_out" | sed -n 's/^ℹ pass \([0-9]*\)$/\1/p' | tail -1)
FE_FAIL=$(printf '%s' "$fe_out" | sed -n 's/^ℹ fail \([0-9]*\)$/\1/p' | tail -1)
FE_PASS=${FE_PASS:-0}; FE_FAIL=${FE_FAIL:-0}
echo "FE_TESTS fe_pass=$FE_PASS fe_fail=$FE_FAIL"

# --- 后端测试（Testcontainers，需 Docker） ---
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
be_log=.auto/last-be.log
(cd ptmcp-parent && mvn -B -ntp '-Dptmcp.test.pgImage=postgres:15' -pl ptmcp-admin,ptmcp-gateway -am test) > "$be_log" 2>&1
BE_EXIT=$?
tail -40 "$be_log" | sed 's/^/BE | /'

# 汇总各模块 Results: 块的 "Tests run:"（避免与逐类行重复计数）
summary=$(awk '/Results:/{f=1;next} f && /Tests run:/{print; f=0}' "$be_log")
BE_RUN=$(printf '%s' "$summary" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
BE_FL=$(printf '%s' "$summary" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
BE_ER=$(printf '%s' "$summary" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p' | awk '{s+=$1} END{print s+0}')
BE_PASS=$((BE_RUN - BE_FL - BE_ER)); BE_FAIL=$((BE_FL + BE_ER))
echo "BE_TESTS be_pass=$BE_PASS be_fail=$BE_FAIL mvn_exit=$BE_EXIT"

echo "METRIC tests_passing=$((FE_PASS + BE_PASS))"
echo "METRIC tests_failed=$((FE_FAIL + BE_FAIL))"
echo "METRIC fe_tests_pass=$FE_PASS"
echo "METRIC be_tests_pass=$BE_PASS"
