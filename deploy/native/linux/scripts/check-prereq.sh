#!/usr/bin/env bash
# 安装前预检（Linux）：配置完整性与占位符、Java 21、nginx、端口、元库可达性、
# 发布包文件与 OAuth 密钥。被自身进程占用的端口视为正常，可在运行中复检。
#
#   check-prereq.sh [--skip-mysql]
#   check-prereq.sh --config /path/to/app.env
#
# 退出码：0 = 全部通过；1 = 至少一项 FAIL。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

SKIP_MYSQL=0
CONFIG_OVERRIDE=''
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-mysql) SKIP_MYSQL=1; shift ;;
    --config) CONFIG_OVERRIDE=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

ptmcp_resolve_context "$0"
[ -n "$CONFIG_OVERRIDE" ] && CONFIG=$CONFIG_OVERRIDE

PASS=0; FAIL=0; WARN=0
pass() { printf '  [PASS] %s - %s\n' "$1" "$2"; PASS=$((PASS + 1)); }
fail() { printf '  [FAIL] %s - %s\n' "$1" "$2" >&2; FAIL=$((FAIL + 1)); }
warn() { printf '  [WARN] %s - %s\n' "$1" "$2"; WARN=$((WARN + 1)); }

echo '== PTMCP 部署预检 =='
echo "部署根：$DEPLOY_ROOT"
echo "版本目录：$RELEASE"
echo "配置文件：$CONFIG"
echo

if [ ! -f "$CONFIG" ]; then
  fail 'config' "配置文件不存在：$CONFIG（先运行 install.sh）"
  echo
  echo "结果：$PASS 通过，$FAIL 失败"
  exit 1
fi
pass 'config' '配置文件存在'
ptmcp_load_config "$CONFIG"

# --- 必填项与占位符 --------------------------------------------------------
missing=''
for key in PTMCP_PUBLIC_BASE_URL PTMCP_METADATA_JDBC_URL PTMCP_METADATA_DB_USERNAME \
  PTMCP_METADATA_DB_PASSWORD PTMCP_CREDENTIAL_KEY PTMCP_AUDIT_KEY PTMCP_GATEWAY_WAL_KEY; do
  val=$(eval "printf '%s' \"\${$key:-}\"")
  case "$val" in
    ''|*CHANGE_ME*) missing="$missing $key" ;;
  esac
done
if [ -z "$missing" ]; then
  pass 'config.values' '必填项已填写'
else
  fail 'config.values' "未填写或仍是 CHANGE_ME：$missing"
fi

case "${PTMCP_PUBLIC_BASE_URL:-}" in
  http://*|https://*) pass 'config.base_url' "$PTMCP_PUBLIC_BASE_URL" ;;
  *) fail 'config.base_url' 'PTMCP_PUBLIC_BASE_URL 必须以 http:// 或 https:// 开头' ;;
esac

case "${PTMCP_METADATA_JDBC_URL:-}" in
  jdbc:mysql://*) pass 'config.jdbc' '元库使用 MySQL JDBC URL' ;;
  *) fail 'config.jdbc' 'PTMCP_METADATA_JDBC_URL 必须是 jdbc:mysql://...' ;;
esac

# --- Java ------------------------------------------------------------------
if command -v "${JAVA_EXE:-java}" >/dev/null 2>&1; then
  ver=$("${JAVA_EXE:-java}" -version 2>&1 | head -n 1)
  major=$(printf '%s' "$ver" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
  if [ -n "$major" ] && [ "$major" -ge 21 ]; then
    pass 'java' "$ver"
  else
    fail 'java' "需要 Java 21+，当前：$ver"
  fi
else
  fail 'java' "找不到 Java：${JAVA_EXE:-java}（安装 JDK 21，或在 app.env 设置 JAVA_EXE）"
fi

# --- nginx -----------------------------------------------------------------
if command -v "${NGINX_EXE:-nginx}" >/dev/null 2>&1; then
  pass 'nginx' "$("${NGINX_EXE:-nginx}" -v 2>&1)"
else
  fail 'nginx' "找不到 nginx：${NGINX_EXE:-nginx}（apt/yum install nginx，或设置 NGINX_EXE）"
fi

# --- 发布包文件 ------------------------------------------------------------
for svc in $PTMCP_SERVICES; do
  jar=$(ptmcp_service_jar "$svc")
  [ -f "$jar" ] && pass "jar.$svc" "$jar" || fail "jar.$svc" "缺少 $jar"
done
[ -f "$RELEASE/frontend/index.html" ] && pass 'frontend' "$RELEASE/frontend" \
  || fail 'frontend' "缺少 SPA 入口：$RELEASE/frontend/index.html"
[ -f "$RELEASE/backend/oauth-pages/oauth/login.html" ] && pass 'oauth-pages' "$RELEASE/backend/oauth-pages" \
  || fail 'oauth-pages' "缺少 OAuth 页面：$RELEASE/backend/oauth-pages/oauth/login.html"
[ -f "$RELEASE/nginx/nginx.conf.template" ] && pass 'nginx.conf' '模板存在' \
  || fail 'nginx.conf' "缺少 $RELEASE/nginx/nginx.conf.template"

KEY_FILE=${PTMCP_OAUTH_SIGNING_KEY_FILE:-$DEPLOY_ROOT/shared/config/secrets/oauth-signing.pem}
if [ -f "$KEY_FILE" ] && head -n 1 "$KEY_FILE" | grep -q 'BEGIN PRIVATE KEY'; then
  pass 'oauth.key' "PKCS#8 私钥：$KEY_FILE"
  other=$(stat -c '%a' "$KEY_FILE" 2>/dev/null || true)
  case "$other" in 600|400) ;; *) warn 'oauth.key.perm' "建议 chmod 600 $KEY_FILE（当前 ${other:-未知}）" ;; esac
else
  fail 'oauth.key' "OAuth 签名私钥缺失或不是未加密 PKCS#8：$KEY_FILE"
fi

# --- 端口 ------------------------------------------------------------------
for svc in $PTMCP_SERVICES; do
  port=$(ptmcp_service_port "$svc")
  if ptmcp_is_running "$svc"; then
    pass "port.$svc" "$port 由本包 $svc 占用"
  elif ptmcp_port_listening "$port"; then
    fail "port.$svc" "端口 $port 已被其他进程占用"
  else
    pass "port.$svc" "$port 空闲"
  fi
done
port=${PTMCP_HTTP_PORT:-8080}
if ptmcp_nginx_running; then
  pass 'port.nginx' "$port 由本包 nginx 占用"
elif ptmcp_port_listening "$port"; then
  fail 'port.nginx' "对外端口 $port 已被其他进程占用"
else
  pass 'port.nginx' "$port 空闲"
fi

# --- 元库 ------------------------------------------------------------------
if [ "$SKIP_MYSQL" -eq 1 ]; then
  warn 'mysql' '已按参数跳过元库检查'
else
  url=${PTMCP_METADATA_JDBC_URL#jdbc:mysql://}
  hostport=${url%%/*}
  dbname=${url#*/}; dbname=${dbname%%\?*}
  host=${hostport%%:*}
  mport=${hostport##*:}
  [ "$host" = "$hostport" ] && mport=3306
  if timeout 5 bash -c "exec 3<>/dev/tcp/$host/$mport" 2>/dev/null; then
    pass 'mysql.tcp' "$host:$mport 可连接"
    if command -v mysql >/dev/null 2>&1; then
      if MYSQL_PWD=$PTMCP_METADATA_DB_PASSWORD mysql --connect-timeout=5 \
        -h "$host" -P "$mport" -u "$PTMCP_METADATA_DB_USERNAME" -N -B \
        -e 'SELECT 1' >/dev/null 2>&1; then
        pass 'mysql.auth' "账号 $PTMCP_METADATA_DB_USERNAME 可登录"
        tables=$(MYSQL_PWD=$PTMCP_METADATA_DB_PASSWORD mysql --connect-timeout=5 \
          -h "$host" -P "$mport" -u "$PTMCP_METADATA_DB_USERNAME" -N -B -e \
          "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$dbname' AND table_name='flyway_schema_history'" 2>/dev/null || echo '?')
        case "$tables" in
          0) warn 'mysql.schema' "库 $dbname 尚未迁移，install 会执行 migrate" ;;
          1) pass 'mysql.schema' "库 $dbname 已由 Flyway 管理" ;;
          *) warn 'mysql.schema' "无法确认迁移状态（需要 mysql 客户端）" ;;
        esac
      else
        fail 'mysql.auth' "账号 $PTMCP_METADATA_DB_USERNAME 无法登录 $host:$mport（密码或授权不对）"
      fi
    else
      warn 'mysql.auth' '未安装 mysql 客户端，跳过账号与迁移状态检查'
    fi
  else
    fail 'mysql.tcp' "无法连接元库 $host:$mport（先启动 MySQL 或改 app.env）"
  fi
fi

echo
printf '结果：%s 通过，%s 失败，%s 警告\n' "$PASS" "$FAIL" "$WARN"
[ "$FAIL" -eq 0 ]
