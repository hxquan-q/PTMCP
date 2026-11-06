#!/usr/bin/env bash
# 对元库执行 Flyway 迁移。
#
# 优先使用本机 flyway CLI（FLYWAY_EXE，默认 PATH 上的 flyway）；否则回退到
# Docker（与 deploy/compose.yaml 同一个固定镜像摘要）。两者都没有时直接失败，
# 不会用自制的迁移记录表替代 Flyway。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

ptmcp_resolve_context "$0"
[ -f "$CONFIG" ] || ptmcp_die "缺少配置：$CONFIG"
ptmcp_load_config "$CONFIG"

MIGRATION_DIR=$RELEASE/migration
[ -d "$MIGRATION_DIR" ] || ptmcp_die "缺少迁移目录：$MIGRATION_DIR"
[ -n "${PTMCP_METADATA_JDBC_URL:-}" ] || ptmcp_die 'app.env 缺少 PTMCP_METADATA_JDBC_URL'

echo "== PTMCP 元库迁移 =="
echo "JDBC：$PTMCP_METADATA_JDBC_URL"
echo "迁移目录：$MIGRATION_DIR"

if command -v "${FLYWAY_EXE:-flyway}" >/dev/null 2>&1; then
  echo '使用本机 flyway CLI。'
  FLYWAY_URL=$PTMCP_METADATA_JDBC_URL \
  FLYWAY_USER=$PTMCP_METADATA_DB_USERNAME \
  FLYWAY_PASSWORD=$PTMCP_METADATA_DB_PASSWORD \
  FLYWAY_LOCATIONS="filesystem:$MIGRATION_DIR" \
  FLYWAY_CONNECT_RETRIES=30 \
    "${FLYWAY_EXE:-flyway}" migrate
  exit $?
fi

if command -v docker >/dev/null 2>&1; then
  echo '未找到 flyway CLI，回退到 Docker（flyway/flyway:11-alpine）。'
  exec docker run --rm --network host \
    -v "$MIGRATION_DIR":/flyway/sql:ro \
    -e FLYWAY_URL="$PTMCP_METADATA_JDBC_URL" \
    -e FLYWAY_USER="$PTMCP_METADATA_DB_USERNAME" \
    -e FLYWAY_PASSWORD="$PTMCP_METADATA_DB_PASSWORD" \
    -e FLYWAY_LOCATIONS=filesystem:/flyway/sql \
    -e FLYWAY_CONNECT_RETRIES=30 \
    flyway/flyway@sha256:df06535dfd6559ae5f0628c4761013dfa4ea6a30cf6e34294931d8091f55a18f \
    migrate
fi

ptmcp_die '需要 flyway CLI 或 Docker 之一来执行迁移。安装 Flyway 后在 app.env 设置 FLYWAY_EXE，或改用 deploy/compose.yaml。'
