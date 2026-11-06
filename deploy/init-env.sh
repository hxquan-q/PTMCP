#!/usr/bin/env bash
# 生成 deploy/.env 与 deploy/secrets/ 下的自托管元库密码文件。
#
# 只在目标主机本地生成密钥，不从网络下载任何凭证。已有 .env 时拒绝覆盖；
# 确需重建时先自行备份并传 --force（会重新生成全部密钥，旧库数据将无法解密）。
set -euo pipefail

BASE="${1:-http://localhost:8080}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$HERE/.env"
SECRET_DIR="$HERE/secrets"
FORCE=0
[ "${2:-}" = "--force" ] && FORCE=1

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"; }

need openssl
case "$BASE" in
    http://*|https://*) ;;
    *) die "基址必须以 http:// 或 https:// 开头：$BASE" ;;
esac
BASE="${BASE%/}"

# 发布端口跟对外基址保持一致，避免浏览器访问端口与实际监听端口对不上。
AUTHORITY="${BASE#*://}"
AUTHORITY="${AUTHORITY%%/*}"
HTTP_PORT=8080
case "$AUTHORITY" in
    *:*:*) ;; # IPv6 字面量不推断端口，保持默认
    *:*) HTTP_PORT="${AUTHORITY##*:}" ;;
esac
case "$HTTP_PORT" in
    ''|*[!0-9]*) die "无法从基址推断端口：$BASE（请写 http://host:port）" ;;
esac

if [ -e "$ENV_FILE" ] && [ "$FORCE" -ne 1 ]; then
    die "$ENV_FILE 已存在。备份后重试，或传 --force 重新生成（会轮换全部密钥）"
fi

umask 077
mkdir -p "$SECRET_DIR"

DB_PASSWORD="$(openssl rand -hex 24)"
printf '%s\n' "$DB_PASSWORD" >"$SECRET_DIR/mysql_app.txt"
printf '%s\n' "$(openssl rand -hex 24)" >"$SECRET_DIR/mysql_root.txt"
# authz 只接受明文 PKCS#8，genpkey 默认即该格式。
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SECRET_DIR/oauth-signing.pem" 2>/dev/null
chmod 600 "$SECRET_DIR/mysql_app.txt" "$SECRET_DIR/mysql_root.txt" "$SECRET_DIR/oauth-signing.pem"

COOKIE_SECURE=false
case "$BASE" in
    https://*) COOKIE_SECURE=true ;;
esac

# 值统一用单引号包裹：compose 会去掉引号，避免 base64 的 + / = 触发转义歧义。
cat >"$ENV_FILE" <<EOF
# 由 deploy/init-env.sh 于 $(date -u '+%Y-%m-%dT%H:%M:%SZ') 生成，含真实密钥，禁止入库。
PTMCP_IMAGE_TAG=0.1.0
PTMCP_HTTP_PORT=$HTTP_PORT
PTMCP_PUBLIC_BASE_URL='$BASE'

PTMCP_METADATA_DB_NAME=ptmcp
PTMCP_METADATA_DB_USERNAME=ptmcp
PTMCP_METADATA_DB_PASSWORD='$DB_PASSWORD'
PTMCP_METADATA_JDBC_URL='jdbc:mysql://mysql:3306/ptmcp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'

PTMCP_CREDENTIAL_KEY='$(openssl rand -base64 32)'
PTMCP_AUDIT_KEY='$(openssl rand -base64 32)'
PTMCP_GATEWAY_WAL_KEY='$(openssl rand -base64 32)'

PTMCP_BUSINESS_ALLOWED_CIDRS=''
PTMCP_BUSINESS_ALLOWED_PORTS='3306,5432'

PTMCP_ADMIN_COOKIE_SECURE=$COOKIE_SECURE
PTMCP_AS_COOKIE_SECURE=$COOKIE_SECURE

PTMCP_OAUTH_ENABLED=true
PTMCP_OAUTH_ISSUER='$BASE'
PTMCP_OAUTH_AUDIENCE='$BASE/mcp'

PTMCP_GATEWAY_ENABLED=true
PTMCP_GATEWAY_ISSUER='$BASE'
PTMCP_GATEWAY_RESOURCE='$BASE/mcp'
PTMCP_GATEWAY_RESOURCE_URI='$BASE/mcp'
PTMCP_GATEWAY_JWKS_URI='http://authz:8081/oauth2/jwks'
PTMCP_GATEWAY_RESOURCE_METADATA_URI='$BASE/.well-known/oauth-protected-resource'
PTMCP_MCP_URL='$BASE/mcp'
EOF
chmod 600 "$ENV_FILE"

printf '已生成 %s 与 %s/{mysql_app.txt,mysql_root.txt,oauth-signing.pem}\n' "$ENV_FILE" "$SECRET_DIR"
printf '对外基址：%s\n' "$BASE"
printf '下一步：cd deploy && docker compose config && docker compose up -d --build\n'
