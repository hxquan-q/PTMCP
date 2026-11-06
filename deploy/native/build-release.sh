#!/usr/bin/env bash
# 组装 Linux / Windows 两个原生部署发布包。
#
#   deploy/native/build-release.sh                 # 构建后端与前端后打包
#   deploy/native/build-release.sh --skip-build    # 直接使用已有产物
#   deploy/native/build-release.sh --skip-build --version 202611061200
#
# 产物：deploy/native/dist/ptmcp-<version>-linux.tar.gz
#       deploy/native/dist/ptmcp-<version>-windows.zip
#
# 发布包结构（两平台一致）：
#   releases/<version>/{backend,frontend,migration,nginx,scripts}
#   shared/config/（空，首次安装时生成 app.env）
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd -P)
ROOT=$(cd "$HERE/../.." && pwd -P)
SKIP_BUILD=0
VERSION=''
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --version) VERSION=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done
VERSION=${VERSION:-$(date -u +%Y%m%d%H%M)}

MIGRATION_SRC=$ROOT/ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration
UI_DIST=$ROOT/ptmcp-ui-react/dist
OAUTH_DIST=$ROOT/ptmcp-ui-react/dist-oauth
JAR_DIR=$ROOT/ptmcp-parent

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo '[build] 后端（跳过测试）...'
  # 下游模块需要 datasource 的 test-jar，仍编译/打包夹具，只跳过执行。
  ( cd "$ROOT/ptmcp-parent" && mvn -B -DskipTests \
      -pl ptmcp-admin,ptmcp-authz,ptmcp-gateway -am package )
  echo '[build] 前端 ...'
  ( cd "$ROOT" && pnpm --ignore-workspace --dir ptmcp-ui-react run build \
      && pnpm --ignore-workspace --dir ptmcp-ui-react run build:oauth )
fi

for f in "$UI_DIST/index.html" "$OAUTH_DIST/oauth/login.html"; do
  [ -e "$f" ] || { echo "缺少前端产物：$f（去掉 --skip-build 或先构建）" >&2; exit 1; }
done
for svc in admin authz gateway; do
  [ -f "$JAR_DIR/ptmcp-$svc/target/ptmcp-$svc-0.1.0-SNAPSHOT.jar" ] \
    || { echo "缺少后端产物：ptmcp-$svc（去掉 --skip-build 或先 mvn package）" >&2; exit 1; }
done

DIST=$HERE/dist
LINUX=$DIST/ptmcp-$VERSION-linux
WIN=$DIST/ptmcp-$VERSION-windows
rm -rf "$LINUX" "$WIN"
mkdir -p "$LINUX" "$WIN"

stage_release() { # <release-root>
  local rel=$1 s
  mkdir -p "$rel/backend/oauth-pages" "$rel/frontend" "$rel/migration" "$rel/nginx" "$rel/scripts"
  for s in admin authz gateway; do
    cp "$JAR_DIR/ptmcp-$s/target/ptmcp-$s-0.1.0-SNAPSHOT.jar" "$rel/backend/$s.jar"
  done
  cp -R "$OAUTH_DIST/." "$rel/backend/oauth-pages/"
  cp -R "$UI_DIST/." "$rel/frontend/"
  cp "$MIGRATION_SRC"/V*.sql "$rel/migration/"
  cp "$HERE/linux/nginx/nginx.conf.template" "$HERE/linux/nginx/mime.types" "$rel/nginx/"
  printf '%s\n' "$VERSION" >"$rel/VERSION"
}

# --- Linux -----------------------------------------------------------------
stage_release "$LINUX/releases/$VERSION"
cp "$HERE/linux/install.sh" "$LINUX/install.sh"
cp "$HERE/linux/scripts"/*.sh "$HERE/linux/scripts/app.env.example" "$LINUX/releases/$VERSION/scripts/"
cp "$ROOT/deploy/base-url.sh" "$LINUX/releases/$VERSION/scripts/"
cp "$HERE/linux/docs/DEPLOY-LINUX.md" "$LINUX/" 2>/dev/null || true
mkdir -p "$LINUX/shared/config" "$LINUX/run" "$LINUX/logs" "$LINUX/temp"
chmod +x "$LINUX/install.sh" "$LINUX/releases/$VERSION/scripts"/*.sh

# --- Windows ---------------------------------------------------------------
stage_release "$WIN/releases/$VERSION"
cp "$HERE/windows/scripts/Platform.Runtime.psm1" "$HERE/windows/scripts/app.env.example" \
  "$WIN/releases/$VERSION/scripts/"
cp "$HERE/windows/nginx/nginx.conf.template" "$HERE/windows/nginx/mime.types" "$WIN/releases/$VERSION/nginx/"
cp "$HERE/windows/platform.ps1" "$HERE/windows/0-install.ps1" "$HERE/windows/init-env.ps1" \
  "$HERE/windows/check-prereq.ps1" "$HERE/windows/migrate.ps1" "$HERE/windows/Ptmcp.Layout.ps1" "$WIN/"
cp "$HERE/windows"/*.cmd "$WIN/"
cp "$HERE/windows/docs/DEPLOY-WINDOWS.md" "$WIN/" 2>/dev/null || true
mkdir -p "$WIN/shared/config" "$WIN/run" "$WIN/logs" "$WIN/temp"

# --- 打包 ------------------------------------------------------------------
( cd "$DIST" && tar -czf "ptmcp-$VERSION-linux.tar.gz" "ptmcp-$VERSION-linux" )
rm -f "$DIST/ptmcp-$VERSION-windows.zip"
if command -v zip >/dev/null 2>&1; then
  ( cd "$DIST" && zip -qr "ptmcp-$VERSION-windows.zip" "ptmcp-$VERSION-windows" )
else
  ( cd "$DIST" && powershell.exe -NoProfile -Command \
      "Compress-Archive -Path 'ptmcp-$VERSION-windows/*' -DestinationPath 'ptmcp-$VERSION-windows.zip' -Force" )
fi

echo
echo "已生成："
ls -lh "$DIST"/ptmcp-"$VERSION"-* 2>/dev/null || true
