#!/usr/bin/env bash
# 生成 shared/config/app.env 与 local OAuth 签名私钥。
#
#   init-env.sh http://<主机>:<端口> [--force]
#
# 密钥只在本机生成（openssl），不从网络下载。已存在 app.env 时拒绝覆盖；
# 确需重建须传 --force，这会轮换全部密钥，旧库中已加密的凭证将无法解密。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

ptmcp_resolve_context "$0"

BASE=${1:-}
FORCE=0
case "${2:-}" in
  --force) FORCE=1 ;;
  '') ;;
  *) ptmcp_die "未知参数：$2" ;;
esac
. "$SCRIPT_DIR/base-url.sh"
ptmcp_parse_base_url "$BASE" || ptmcp_die '基址须为无路径/凭证/query/fragment 的 HTTPS URL，或 localhost、127.0.0.1、[::1] 的 HTTP URL；端口为 1..65535'

ptmcp_ensure_dirs
CONFIG=$DEPLOY_ROOT/shared/config/app.env
[ -e "$CONFIG" ] && [ "$FORCE" -ne 1 ] && \
  ptmcp_die "$CONFIG 已存在。备份后重试，或传 --force 重新生成（会轮换全部密钥）"
command -v openssl >/dev/null 2>&1 || ptmcp_die '缺少 openssl'

TPL=$SCRIPT_DIR/app.env.example
[ -f "$TPL" ] || ptmcp_die "缺少模板：$TPL"

COOKIE_SECURE=false
case "$BASE" in https://*) COOKIE_SECURE=true ;; esac

umask 077
CREDENTIAL_KEY=$(openssl rand -base64 32)
AUDIT_KEY=$(openssl rand -base64 32)
WAL_KEY=$(openssl rand -base64 32)
KEY_FILE=$DEPLOY_ROOT/shared/config/secrets/oauth-signing.pem
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY_FILE" 2>/dev/null \
  || ptmcp_die '生成 OAuth 签名私钥失败'

sed \
  -e "s|@HTTP_PORT@|$HTTP_PORT|g" \
  -e "s|@PUBLIC_BASE_URL@|$BASE|g" \
  -e "s|@COOKIE_SECURE@|$COOKIE_SECURE|g" \
  -e "s|@CREDENTIAL_KEY@|$CREDENTIAL_KEY|g" \
  -e "s|@AUDIT_KEY@|$AUDIT_KEY|g" \
  -e "s|@WAL_KEY@|$WAL_KEY|g" \
  "$TPL" >"$CONFIG"
chmod 600 "$CONFIG" "$KEY_FILE"

cat <<EOF
已生成：
  $CONFIG
  $KEY_FILE

接下来：
  1. 编辑 $CONFIG 填写元库连接（PTMCP_METADATA_*），其余键已自动生成；
  2. 运行 install.sh（会自动预检、迁移并启动）。
EOF
