#!/usr/bin/env bash
# 迁移完成后显式配置运行账号；可重复执行，不依赖空卷初始化，不输出凭证。
set -euo pipefail
# 迁移用户由 MYSQL_USER 创建；三个运行账号只有各自表/操作权限。
[[ $MYSQL_DATABASE =~ ^[a-zA-Z0-9_]{1,64}$ ]] || { echo 'Invalid metadata database name' >&2; exit 1; }
export MYSQL_PWD="$(cat /mysql-secrets/mysql_root_password)"
ptmcp_sql() { mysql --protocol=TCP -h mysql -uroot --batch --skip-column-names "$@"; }
ptmcp_grant() {
    local service=$1 privileges=$2 table
    shift 2
    for table in "$@"; do
        ptmcp_sql <<< "GRANT $privileges ON \`$MYSQL_DATABASE\`.\`$table\` TO 'ptmcp_$service'@'%';"
    done
}
for service in admin authz gateway; do
    password=$(cat "/mysql-secrets/mysql_${service}_password")
    [[ $password =~ ^[0-9a-fA-F]{48}$ ]] || { echo 'Runtime passwords must be generated 48-character hex values' >&2; exit 1; }
    ptmcp_sql <<SQL
CREATE USER IF NOT EXISTS 'ptmcp_$service'@'%' IDENTIFIED BY '$password';
ALTER USER 'ptmcp_$service'@'%' IDENTIFIED BY '$password';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ptmcp_$service'@'%';
SQL
done
unset password

# admin 管理目录、身份绑定、元数据采集与平台配置；审计内容和执行租约不授修改权。
ptmcp_grant admin SELECT,INSERT,UPDATE \
    sys_dept sys_role sys_user_profile \
    sys_user_security identity_password_change identity_provision acl_grant platform_registry \
    datasource datasource_candidate resource_namespace resource_object \
    schema_snapshot metadata_sync_job oauth2_registered_client personal_token web_session_index
ptmcp_grant admin SELECT,INSERT sys_user_login_name resource_column snapshot_object
ptmcp_grant admin SELECT,UPDATE sys_security_state
ptmcp_grant admin INSERT sys_security_event
ptmcp_grant admin SELECT,INSERT,UPDATE,DELETE api_idempotency
ptmcp_grant admin SELECT,INSERT,DELETE sys_user_role platform_scope business_dictionary
ptmcp_grant admin SELECT audit_log
ptmcp_grant admin SELECT,INSERT admin_operation_log audit_sensitive_access
ptmcp_grant admin SELECT oauth2_authorization oauth_refresh_family oauth_refresh_token
ptmcp_grant admin 'UPDATE(status,version,update_time)' oauth2_authorization
ptmcp_grant admin 'UPDATE(status,revoked_at,revoke_reason,version,update_time)' oauth_refresh_family
ptmcp_grant admin 'UPDATE(status,update_time)' oauth_refresh_token

# authz 只读平台/员工状态，持久化 OAuth 与自己的 Web 会话。
ptmcp_grant authz SELECT sys_user_profile sys_user_login_name sys_user_security platform_registry oauth2_registered_client
ptmcp_grant authz SELECT,UPDATE sys_security_state
ptmcp_grant authz INSERT sys_security_event
ptmcp_grant authz SELECT,INSERT,UPDATE,DELETE oauth2_authorization oauth2_authorization_consent
ptmcp_grant authz SELECT,INSERT,UPDATE oauth_refresh_family oauth_refresh_token web_session_index

# gateway 读取身份/ACL/发布快照，写自己的审计与执行租约；不能修改授权或密码。
ptmcp_grant gateway SELECT sys_security_state sys_user_security sys_user_profile sys_dept sys_role \
    sys_user_role platform_registry platform_scope acl_grant datasource resource_namespace \
    resource_object resource_column schema_snapshot snapshot_object business_dictionary \
    oauth2_registered_client oauth_refresh_family personal_token
ptmcp_grant gateway SELECT,INSERT,UPDATE audit_boot_owner execution_lease audit_outbox audit_log

# sys_user 归宿主管理，不创建/迁移它；同库身份表已接入时才授权。
if [ "$(ptmcp_sql -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$MYSQL_DATABASE' AND table_name='sys_user'")" = 1 ]; then
    ptmcp_grant admin SELECT,INSERT,UPDATE sys_user
    ptmcp_grant authz SELECT sys_user
fi
