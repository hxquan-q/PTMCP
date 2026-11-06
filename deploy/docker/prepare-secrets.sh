#!/usr/bin/env bash
# 文件型 Compose secret 保留宿主 0600；只在专属卷内转换运行身份，不改宿主权限。
set -euo pipefail
umask 077
mkdir -p /mysql-secrets /authz-key
for name in mysql_root_password mysql_migrate_password mysql_admin_password mysql_authz_password mysql_gateway_password; do
    cp "/run/secrets/$name" "/mysql-secrets/$name"
    chmod 400 "/mysql-secrets/$name"
    chown 999:999 "/mysql-secrets/$name"
done
cp /run/secrets/oauth_signing_key /authz-key/oauth-signing.pem
chmod 400 /authz-key/oauth-signing.pem
chown 10001:10001 /authz-key/oauth-signing.pem
chmod 700 /mysql-secrets /authz-key
chown 999:999 /mysql-secrets
chown 10001:10001 /authz-key
