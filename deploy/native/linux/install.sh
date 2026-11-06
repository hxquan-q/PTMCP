#!/usr/bin/env bash
# PTMCP · Linux 首次安装（解压发布包后运行一次，可重复执行续跑）。
#
#   ./install.sh http://<主机>:<端口> [--skip-migrate] [--no-start]
#
# 步骤：
#   [1/5] 选择 releases/ 下的最新版本，建立 current 符号链接
#   [2/5] 生成共享配置 shared/config/app.env 与本地密钥（已存在则保留）
#   [3/5] 预检：Java 21 / nginx / 端口 / 元库 / 配置
#   [4/5] Flyway 迁移元库
#   [5/5] 启动 admin → authz → gateway → nginx，并等待健康
set -uo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd -P)
BASE=''
SKIP_MIGRATE=0
NO_START=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-migrate) SKIP_MIGRATE=1; shift ;;
    --no-start) NO_START=1; shift ;;
    http://*|https://*) BASE=$1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

fail_exit() {
  echo
  echo "[失败] $1"
  echo '       修复后重新运行 install.sh 即可续跑，已完成的步骤不会重来。'
  exit 1
}

echo '============================================================'
echo '  PTMCP · Linux 首次安装'
echo "  部署根目录: $ROOT"
echo '============================================================'
echo

RELEASES_DIR=$ROOT/releases
[ -d "$RELEASES_DIR" ] || fail_exit "没有找到 $RELEASES_DIR；请把发布包整体解压后再运行"
REL_NAME=$(LC_ALL=C ls -1 "$RELEASES_DIR" | LC_ALL=C sort -r | head -n 1)
[ -n "$REL_NAME" ] || fail_exit "$RELEASES_DIR 里没有版本目录"
REL=$RELEASES_DIR/$REL_NAME

echo "[1/5] 选中版本 $REL_NAME，建立 current 链接 ..."
chmod +x "$REL"/scripts/*.sh 2>/dev/null || true
if [ -e "$ROOT/current" ] || [ -L "$ROOT/current" ]; then
  if [ -L "$ROOT/current" ]; then
    ln -sfn "$REL" "$ROOT/current"
  elif [ -z "$(ls -A "$ROOT/current" 2>/dev/null)" ]; then
    rmdir "$ROOT/current" && ln -s "$REL" "$ROOT/current"
  else
    fail_exit "$ROOT/current 是非空目录，拒绝覆盖；请人工确认后移走"
  fi
else
  ln -s "$REL" "$ROOT/current"
fi

echo
echo '[2/5] 共享配置 shared/config/app.env ...'
CONFIG=$ROOT/shared/config/app.env
if [ -f "$CONFIG" ]; then
  echo "  已存在，保留：$CONFIG"
else
  [ -n "$BASE" ] || fail_exit "首次安装需要对外基址，例如：./install.sh http://localhost:8080"
  "$REL/scripts/init-env.sh" "$BASE" || fail_exit '生成配置失败'
  echo
  echo '  请先编辑上面的 app.env：填写 PTMCP_METADATA_* 元库连接，再重新运行 install.sh。'
  exit 1
fi

echo
echo '[3/5] 预检 ...'
"$REL/scripts/check-prereq.sh" --config "$CONFIG" || fail_exit '预检未通过'

if [ "$SKIP_MIGRATE" -eq 1 ]; then
  echo
  echo '[4/5] 已按参数跳过元库迁移'
else
  echo
  echo '[4/5] 迁移元库 ...'
  "$REL/scripts/migrate.sh" || fail_exit '元库迁移失败'
fi

if [ "$NO_START" -eq 1 ]; then
  echo
  echo '[5/5] 已按参数跳过启动；需要时运行：current/scripts/platform.sh start'
  exit 0
fi

echo
echo '[5/5] 启动服务 ...'
"$REL/scripts/platform.sh" start || fail_exit "启动失败；日志在 $ROOT/logs/"

BASE_URL=$(sed -n 's/^PTMCP_PUBLIC_BASE_URL=//p' "$CONFIG" | tail -n 1 | tr -d "'\"")
echo
echo '============================================================'
echo '  安装完成'
echo "  访问地址：${BASE_URL:-http://<主机>:<端口>}"
echo "  状态：    $REL/scripts/platform.sh status"
echo "  日志：    $REL/scripts/platform.sh logs"
echo '  注意：当前版本尚未播种管理员账号，登录流程在后续切片提供。'
echo '============================================================'
