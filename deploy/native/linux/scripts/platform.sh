#!/usr/bin/env bash
# PTMCP 托管入口（Linux）。
#
#   platform.sh start            启动 admin → authz → gateway → nginx（逐个等健康）
#   platform.sh stop             逆序停止，只停身份复核通过的进程
#   platform.sh restart
#   platform.sh status
#   platform.sh check            前置检查（Java/nginx/端口/元库/配置）
#   platform.sh migrate          对元库执行 Flyway 迁移
#   platform.sh logs [服务名]    跟踪日志（默认全部）
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

ptmcp_resolve_context "$0"
[ -f "$CONFIG" ] || ptmcp_die "缺少配置：$CONFIG（先运行 install.sh 生成）"
ptmcp_load_config "$CONFIG"

ACTION=${1:-}
shift 2>/dev/null || true

start_all() {
  ptmcp_ensure_dirs
  ptmcp_export_app_env
  local svc timeout=${PTMCP_START_TIMEOUT:-240}
  for svc in $PTMCP_SERVICES; do
    ptmcp_start_service "$svc"
    ptmcp_wait_health "$svc" "$timeout" || ptmcp_die "$svc 在 ${timeout}s 内未就绪，日志：$DEPLOY_ROOT/logs/$svc.log"
  done
  ptmcp_start_nginx
}

stop_all() {
  ptmcp_stop_nginx
  local svc
  for svc in gateway authz admin; do
    ptmcp_stop_service "$svc"
  done
}

case "$ACTION" in
  start)
    start_all
    ;;
  stop)
    stop_all
    ;;
  restart)
    stop_all
    start_all
    ;;
  status)
    ptmcp_status
    ;;
  check)
    exec "$SCRIPT_DIR/check-prereq.sh" "$@"
    ;;
  migrate)
    exec "$SCRIPT_DIR/migrate.sh" "$@"
    ;;
  logs)
    if [ "$#" -gt 0 ]; then
      exec tail -n 100 -F "$DEPLOY_ROOT/logs/$1.log"
    fi
    exec tail -n 50 -F "$DEPLOY_ROOT"/logs/admin.log "$DEPLOY_ROOT"/logs/authz.log \
      "$DEPLOY_ROOT"/logs/gateway.log "$DEPLOY_ROOT"/logs/nginx-error.log
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
