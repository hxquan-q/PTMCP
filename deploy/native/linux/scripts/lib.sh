#!/usr/bin/env bash
# PTMCP 原生部署公共库（Linux）。
#
# 安装后的发布包布局（install.sh 建立）：
#   <deploy-root>/
#     install.sh
#     releases/<version>/{scripts,nginx,backend,frontend,migration}
#     shared/config/app.env          # 外置配置，升级不覆盖
#     shared/config/secrets/         # 本地生成的密钥
#     shared/data/wal/               # 网关 WAL（跨版本保留）
#     run/  logs/  temp/
#
# 本文件只定义函数与常量，不执行动作。调用方负责 set -uo pipefail。
# 进程归属：每个后端用 PID + /proc/<pid>/stat 启动时间 + 精确命令行标记
# （-Dptmcp.service=<name>）三重复核；绝不按进程名杀 Java。

PTMCP_SERVICES='admin authz gateway'

ptmcp_die() { printf 'error: %s\n' "$*" >&2; exit 1; }
ptmcp_info() { printf '%s\n' "$*"; }

ptmcp_trim() {
  local s=$1
  s=${s#"${s%%[![:space:]]*}"}
  s=${s%"${s##*[![:space:]]}"}
  printf '%s' "$s"
}

# 解析 app.env（KEY=VALUE，支持整行注释与成对引号），不 eval。
ptmcp_load_config() {
  local file=$1 line key val
  [ -f "$file" ] || ptmcp_die "找不到配置文件：$file"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key=$(ptmcp_trim "${line%%=*}")
    val=$(ptmcp_trim "${line#*=}")
    case "$key" in
      [A-Za-z_]*) ;;
      *) continue ;;
    esac
    case "$key" in *[!A-Za-z0-9_]*) continue ;; esac
    case "$val" in
      \'*\') val=${val#\'}; val=${val%\'} ;;
      \"*\") val=${val#\"}; val=${val%\"} ;;
    esac
    export "$key=$val"
  done <"$file"
}

# 从脚本目录推导部署根与当前版本目录。
# 只接受 <root>/releases/<version>/scripts 这一种布局；源码树请先 build-release.sh。
ptmcp_resolve_context() {
  local script_dir deploy_root release
  script_dir=$(cd "$(dirname "$1")" && pwd -P)
  deploy_root=$(cd "$script_dir/../../.." && pwd -P)
  case "$script_dir" in
    */releases/*/scripts) ;;
    *) ptmcp_die "脚本不在 releases/<version>/scripts 下：$script_dir（发布包未按布局组装？）" ;;
  esac
  release=$(cd "$script_dir/.." && pwd -P)
  DEPLOY_ROOT=$deploy_root
  RELEASE=$release
  CONFIG=${PTMCP_CONFIG:-$DEPLOY_ROOT/shared/config/app.env}
  export DEPLOY_ROOT RELEASE CONFIG
}

ptmcp_ensure_dirs() {
  mkdir -p "$DEPLOY_ROOT/run" "$DEPLOY_ROOT/logs" "$DEPLOY_ROOT/temp" \
    "$DEPLOY_ROOT/shared/config/secrets" "$DEPLOY_ROOT/shared/data/wal"
}

ptmcp_service_port() {
  case "$1" in
    admin) printf '%s' "${PTMCP_ADMIN_PORT:-18080}" ;;
    authz) printf '%s' "${PTMCP_AUTHZ_PORT:-18081}" ;;
    gateway) printf '%s' "${PTMCP_GATEWAY_PORT:-18082}" ;;
    *) ptmcp_die "未知服务：$1" ;;
  esac
}

ptmcp_service_jar() { printf '%s' "$RELEASE/backend/$1.jar"; }

ptmcp_pidfile() { printf '%s' "$DEPLOY_ROOT/run/$1.pid"; }

ptmcp_java() {
  local java=${JAVA_EXE:-java}
  command -v "$java" >/dev/null 2>&1 || ptmcp_die "找不到 Java：$java（在 app.env 里设置 JAVA_EXE）"
  printf '%s' "$java"
}

ptmcp_proc_starttime() {
  local pid=$1
  [ -r "/proc/$pid/stat" ] || return 1
  awk '{print $22}' "/proc/$pid/stat" 2>/dev/null
}

ptmcp_port_listening() {
  local port=$1 table
  if command -v ss >/dev/null 2>&1; then
    table=$(ss -ltn 2>/dev/null) || table=''
  else
    table=$(netstat -ltn 2>/dev/null) || table=''
  fi
  [ -n "$table" ] || return 1
  # 不用 grep -q：提前退出会让上游收到 SIGPIPE，在 pipefail 下产生假失败。
  printf '%s\n' "$table" | awk '{print $4}' | grep -E "[:.]${port}\$" >/dev/null
}

# 运行且身份复核通过返回 0；PID 已消失则清理残留记录并返回 1。
ptmcp_is_running() {
  local svc=$1 pidfile pid recorded now cmdline
  pidfile=$(ptmcp_pidfile "$svc")
  [ -f "$pidfile" ] || return 1
  read -r pid recorded <"$pidfile" || true
  case "$pid" in ''|*[!0-9]*) rm -f "$pidfile"; return 1 ;; esac
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$pidfile"
    return 1
  fi
  now=$(ptmcp_proc_starttime "$pid") || { rm -f "$pidfile"; return 1; }
  [ "$now" = "$recorded" ] || return 1
  cmdline=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null) || return 1
  case "$cmdline" in
    *"-Dptmcp.service=$svc"*) return 0 ;;
    *) return 1 ;;
  esac
}

# 把共享元库配置展开成三个应用各自的变量；与 deploy/compose.yaml 的映射一致。
ptmcp_export_app_env() {
  local base=${PTMCP_PUBLIC_BASE_URL:-}
  [ -n "$base" ] || ptmcp_die "app.env 缺少 PTMCP_PUBLIC_BASE_URL"
  base=${base%/}
  [ -n "${PTMCP_METADATA_JDBC_URL:-}" ] || ptmcp_die "app.env 缺少 PTMCP_METADATA_JDBC_URL"
  [ -n "${PTMCP_METADATA_DB_USERNAME:-}" ] || ptmcp_die "app.env 缺少 PTMCP_METADATA_DB_USERNAME"
  [ -n "${PTMCP_METADATA_DB_PASSWORD:-}" ] || ptmcp_die "app.env 缺少 PTMCP_METADATA_DB_PASSWORD"
  case "${PTMCP_METADATA_DB_PASSWORD}" in *CHANGE_ME*) ptmcp_die 'app.env 里 PTMCP_METADATA_DB_PASSWORD 仍是 CHANGE_ME' ;; esac
  case "${PTMCP_CREDENTIAL_KEY:-}" in ''|*CHANGE_ME*) ptmcp_die 'app.env 里 PTMCP_CREDENTIAL_KEY 未填写' ;; esac
  case "${PTMCP_AUDIT_KEY:-}" in ''|*CHANGE_ME*) ptmcp_die 'app.env 里 PTMCP_AUDIT_KEY 未填写' ;; esac
  case "${PTMCP_GATEWAY_WAL_KEY:-}" in ''|*CHANGE_ME*) ptmcp_die 'app.env 里 PTMCP_GATEWAY_WAL_KEY 未填写' ;; esac

  export SERVER_ADDRESS=127.0.0.1
  export PTMCP_ADMIN_DATASOURCE_URL=$PTMCP_METADATA_JDBC_URL
  export PTMCP_ADMIN_DATASOURCE_USERNAME=$PTMCP_METADATA_DB_USERNAME
  export PTMCP_ADMIN_DATASOURCE_PASSWORD=$PTMCP_METADATA_DB_PASSWORD
  export PTMCP_ADMIN_IDENTITY_DATASOURCE_URL=$PTMCP_METADATA_JDBC_URL
  export PTMCP_ADMIN_IDENTITY_DATASOURCE_USERNAME=$PTMCP_METADATA_DB_USERNAME
  export PTMCP_ADMIN_IDENTITY_DATASOURCE_PASSWORD=$PTMCP_METADATA_DB_PASSWORD
  export PTMCP_ADMIN_AUDITKEY=$PTMCP_AUDIT_KEY
  export PTMCP_ADMIN_CREDENTIALKEY=$PTMCP_CREDENTIAL_KEY
  export PTMCP_ADMIN_COOKIESECURE=${PTMCP_ADMIN_COOKIE_SECURE:-false}
  export PTMCP_ADMIN_DATASOURCE_ALLOWEDCIDRS=${PTMCP_BUSINESS_ALLOWED_CIDRS:-}
  export PTMCP_ADMIN_DATASOURCE_ALLOWEDPORTS=${PTMCP_BUSINESS_ALLOWED_PORTS:-3306,5432}

  export PTMCP_AUTHZ_DB_URL=$PTMCP_METADATA_JDBC_URL
  export PTMCP_AUTHZ_DB_USERNAME=$PTMCP_METADATA_DB_USERNAME
  export PTMCP_AUTHZ_DB_PASSWORD=$PTMCP_METADATA_DB_PASSWORD
  export PTMCP_OAUTH_ENABLED=${PTMCP_OAUTH_ENABLED:-true}
  export PTMCP_OAUTH_ISSUER=${PTMCP_OAUTH_ISSUER:-$base}
  export PTMCP_OAUTH_AUDIENCE=${PTMCP_OAUTH_AUDIENCE:-$base/mcp}
  export PTMCP_OAUTH_SIGNING_KEY_FILE=${PTMCP_OAUTH_SIGNING_KEY_FILE:-$DEPLOY_ROOT/shared/config/secrets/oauth-signing.pem}
  export PTMCP_AUTHZ_OAUTH_PAGES_DIR=$RELEASE/backend/oauth-pages
  export PTMCP_AS_COOKIE_SECURE=${PTMCP_AS_COOKIE_SECURE:-false}

  export PTMCP_GATEWAY_ENABLED=${PTMCP_GATEWAY_ENABLED:-true}
  export PTMCP_GATEWAY_METADATAURL=$PTMCP_METADATA_JDBC_URL
  export PTMCP_GATEWAY_METADATAUSERNAME=$PTMCP_METADATA_DB_USERNAME
  export PTMCP_GATEWAY_METADATAPASSWORD=$PTMCP_METADATA_DB_PASSWORD
  export PTMCP_GATEWAY_CREDENTIALKEY=$PTMCP_CREDENTIAL_KEY
  export PTMCP_GATEWAY_AUDITKEY=$PTMCP_AUDIT_KEY
  export PTMCP_GATEWAY_WALKEY=$PTMCP_GATEWAY_WAL_KEY
  export PTMCP_GATEWAY_WALDIRECTORY=$DEPLOY_ROOT/shared/data/wal
  export PTMCP_GATEWAY_ISSUER=${PTMCP_GATEWAY_ISSUER:-$base}
  export PTMCP_GATEWAY_RESOURCE=${PTMCP_GATEWAY_RESOURCE:-$base/mcp}
  export PTMCP_GATEWAY_RESOURCEURI=${PTMCP_GATEWAY_RESOURCEURI:-$base/mcp}
  export PTMCP_GATEWAY_JWKSURI=${PTMCP_GATEWAY_JWKSURI:-http://127.0.0.1:$(ptmcp_service_port authz)/oauth2/jwks}
  export PTMCP_GATEWAY_RESOURCEMETADATAURI=${PTMCP_GATEWAY_RESOURCEMETADATAURI:-$base/.well-known/oauth-protected-resource}
  export PTMCP_GATEWAY_ALLOWEDCIDRS=${PTMCP_BUSINESS_ALLOWED_CIDRS:-}
  export PTMCP_GATEWAY_ALLOWEDPORTS=${PTMCP_BUSINESS_ALLOWED_PORTS:-3306,5432}
  export PTMCP_MCP_URL=${PTMCP_MCP_URL:-$base/mcp}
}

ptmcp_http_get() {
  local url=$1
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 3 "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O - "$url"
  else
    return 127
  fi
}

ptmcp_wait_health() {
  local svc=$1 timeout=${2:-240} port deadline
  port=$(ptmcp_service_port "$svc")
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    ptmcp_is_running "$svc" || ptmcp_die "$svc 进程已退出，日志：$DEPLOY_ROOT/logs/$svc.log"
    if ptmcp_http_get "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      ptmcp_info "  $svc 就绪（127.0.0.1:$port）"
      return 0
    fi
    sleep 2
  done
  return 1
}

ptmcp_start_service() {
  local svc=$1 jar port java pid starttime
  ptmcp_is_running "$svc" && { ptmcp_info "  $svc 已在运行（PID $(cut -d' ' -f1 "$(ptmcp_pidfile "$svc")")）"; return 0; }
  port=$(ptmcp_service_port "$svc")
  ptmcp_port_listening "$port" && ptmcp_die "端口 $port 已被其他进程占用，拒绝启动 $svc"
  jar=$(ptmcp_service_jar "$svc")
  [ -f "$jar" ] || ptmcp_die "缺少 jar：$jar"
  java=$(ptmcp_java)

  ptmcp_export_app_env
  # shellcheck disable=SC2086
  ( export SERVER_PORT=$port; exec "$java" ${PTMCP_JAVA_OPTS:-} -Dptmcp.service="$svc" -jar "$jar" ) \
    >>"$DEPLOY_ROOT/logs/$svc.log" 2>&1 &
  pid=$!
  starttime=$(ptmcp_proc_starttime "$pid") || ptmcp_die "无法读取 $svc 进程信息"
  printf '%s %s\n' "$pid" "$starttime" >"$(ptmcp_pidfile "$svc")"
  sleep 1
  kill -0 "$pid" 2>/dev/null || ptmcp_die "$svc 启动即退出，日志：$DEPLOY_ROOT/logs/$svc.log"
  ptmcp_info "  $svc 已启动（PID $pid，端口 $port）"
}

# 只终止通过身份复核的进程；不 SIGKILL。
ptmcp_stop_service() {
  local svc=$1 pid recorded deadline
  if ! ptmcp_is_running "$svc"; then
    rm -f "$(ptmcp_pidfile "$svc")"
    ptmcp_info "  $svc 未在运行"
    return 0
  fi
  read -r pid recorded <"$(ptmcp_pidfile "$svc")"
  kill -TERM "$pid" 2>/dev/null || true
  deadline=$(( $(date +%s) + ${PTMCP_STOP_TIMEOUT:-60} ))
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      ptmcp_die "$svc（PID $pid）在 ${PTMCP_STOP_TIMEOUT:-60}s 内未退出；已保留 PID 记录，请人工检查"
    fi
    sleep 1
  done
  rm -f "$(ptmcp_pidfile "$svc")"
  ptmcp_info "  $svc 已停止"
}

ptmcp_nginx_bin() {
  local bin=${NGINX_EXE:-nginx}
  command -v "$bin" >/dev/null 2>&1 || ptmcp_die "找不到 nginx：$bin（在 app.env 里设置 NGINX_EXE）"
  printf '%s' "$bin"
}

ptmcp_nginx_conf() { printf '%s' "$DEPLOY_ROOT/run/nginx.conf"; }

ptmcp_render_nginx() {
  local tpl=$RELEASE/nginx/nginx.conf.template out port
  [ -f "$tpl" ] || ptmcp_die "缺少 nginx 模板：$tpl"
  port=${PTMCP_HTTP_PORT:-8080}
  out=$(ptmcp_nginx_conf)
  sed \
    -e "s|@HTTP_PORT@|$port|g" \
    -e "s|@ADMIN_PORT@|$(ptmcp_service_port admin)|g" \
    -e "s|@AUTHZ_PORT@|$(ptmcp_service_port authz)|g" \
    -e "s|@GATEWAY_PORT@|$(ptmcp_service_port gateway)|g" \
    -e "s|@FRONTEND_DIR@|$RELEASE/frontend|g" \
    -e "s|@NGINX_DIR@|$RELEASE/nginx|g" \
    -e "s|@RUN_DIR@|$DEPLOY_ROOT/run|g" \
    -e "s|@LOG_DIR@|$DEPLOY_ROOT/logs|g" \
    -e "s|@TEMP_DIR@|$DEPLOY_ROOT/temp|g" \
    "$tpl" >"$out"
  printf '%s' "$out"
}

ptmcp_start_nginx() {
  local bin conf port pidfile pid starttime
  bin=$(ptmcp_nginx_bin)
  if ptmcp_nginx_running; then
    ptmcp_info "  nginx 已在运行"
    return 0
  fi
  port=${PTMCP_HTTP_PORT:-8080}
  ptmcp_port_listening "$port" && ptmcp_die "端口 $port 已被其他进程占用，拒绝启动 nginx"
  conf=$(ptmcp_render_nginx)
  "$bin" -t -c "$conf" >/dev/null 2>&1 || ptmcp_die "nginx 配置校验失败：$bin -t -c $conf"
  "$bin" -c "$conf" || ptmcp_die "nginx 启动失败，见 $DEPLOY_ROOT/logs/nginx-error.log"
  pidfile="$DEPLOY_ROOT/run/nginx.pid"
  [ -f "$pidfile" ] || ptmcp_die "nginx 启动后未生成 $pidfile"
  pid=$(cat "$pidfile")
  starttime=$(ptmcp_proc_starttime "$pid") || true
  printf 'nginx %s\n' "$starttime" >"$DEPLOY_ROOT/run/nginx.meta"
  ptmcp_info "  nginx 已启动（PID $pid，端口 $port）"
}

ptmcp_nginx_running() {
  local pid starttime recorded
  [ -f "$DEPLOY_ROOT/run/nginx.meta" ] || return 1
  [ -f "$DEPLOY_ROOT/run/nginx.pid" ] || return 1
  pid=$(cat "$DEPLOY_ROOT/run/nginx.pid" 2>/dev/null) || return 1
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$pid" 2>/dev/null || return 1
  recorded=$(awk '{print $2}' "$DEPLOY_ROOT/run/nginx.meta")
  starttime=$(ptmcp_proc_starttime "$pid") || return 1
  [ "$starttime" = "$recorded" ] || return 1
  return 0
}

ptmcp_stop_nginx() {
  local bin conf pid deadline
  if ! ptmcp_nginx_running; then
    rm -f "$DEPLOY_ROOT/run/nginx.pid" "$DEPLOY_ROOT/run/nginx.meta"
    ptmcp_info "  nginx 未在运行"
    return 0
  fi
  bin=$(ptmcp_nginx_bin)
  conf=$(ptmcp_nginx_conf)
  pid=$(cat "$DEPLOY_ROOT/run/nginx.pid")
  "$bin" -s quit -c "$conf" 2>/dev/null || kill -QUIT "$pid" 2>/dev/null || true
  deadline=$(( $(date +%s) + ${PTMCP_STOP_TIMEOUT:-60} ))
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      ptmcp_die "nginx（PID $pid）在 ${PTMCP_STOP_TIMEOUT:-60}s 内未退出"
    fi
    sleep 1
  done
  rm -f "$DEPLOY_ROOT/run/nginx.pid" "$DEPLOY_ROOT/run/nginx.meta"
  ptmcp_info "  nginx 已停止"
}

ptmcp_status() {
  local svc port state
  for svc in $PTMCP_SERVICES; do
    port=$(ptmcp_service_port "$svc")
    if ptmcp_is_running "$svc"; then
      state="running PID $(cut -d' ' -f1 "$(ptmcp_pidfile "$svc")")"
    else
      state='stopped'
    fi
    printf '%-8s %-6s %s\n' "$svc" "$port" "$state"
  done
  if ptmcp_nginx_running; then
    printf '%-8s %-6s %s\n' nginx "${PTMCP_HTTP_PORT:-8080}" "running PID $(cat "$DEPLOY_ROOT/run/nginx.pid")"
  else
    printf '%-8s %-6s %s\n' nginx "${PTMCP_HTTP_PORT:-8080}" stopped
  fi
}
