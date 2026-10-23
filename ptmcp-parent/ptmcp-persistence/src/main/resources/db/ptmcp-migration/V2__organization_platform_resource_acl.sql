-- PTMCP metadata only, MySQL 8.4 / InnoDB / utf8mb4, UTC DATETIME(6), no ON UPDATE.
-- This target has no RuoYi base tables: sys_dept/sys_role/sys_user_role are new minimal
-- equivalents created here, not upstream DDL reuse. sys_user is intentionally absent, so
-- sys_user_login_name has no FK; the identity adapter owns that binding.
-- Versioned release unit, not transactional DDL. No credentials or seed accounts.

CREATE TABLE platform_registry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    tier TINYINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    catalog_mode VARCHAR(16) NOT NULL,
    proof_mode VARCHAR(16) NOT NULL,
    proof_config_cipher BLOB NULL,
    proof_key_id VARCHAR(128) NULL,
    scope_version BIGINT NOT NULL DEFAULT 1,
    security_version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_registry_code (code),
    CONSTRAINT chk_platform_registry_tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT chk_platform_registry_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT chk_platform_registry_catalog CHECK (catalog_mode IN ('DISCOVER', 'HIDE')),
    CONSTRAINT chk_platform_registry_proof CHECK (proof_mode IN ('MTLS', 'PROXY_SECRET', 'NONE')),
    CONSTRAINT chk_platform_registry_tier1_proof CHECK (tier <> 1 OR proof_mode <> 'NONE'),
    CONSTRAINT chk_platform_registry_scope_version CHECK (scope_version > 0),
    CONSTRAINT chk_platform_registry_security_version CHECK (security_version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE datasource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    engine VARCHAR(16) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(128) NOT NULL,
    username VARCHAR(128) NOT NULL,
    credential_cipher BLOB NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    tls_mode VARCHAR(16) NOT NULL,
    pool_max SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    config_version BIGINT NOT NULL DEFAULT 1,
    resource_version BIGINT NOT NULL DEFAULT 1,
    active_snapshot_id BIGINT NULL,
    last_sync_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_datasource_code (code),
    CONSTRAINT chk_datasource_engine CHECK (engine IN ('MYSQL', 'POSTGRESQL')),
    CONSTRAINT chk_datasource_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT chk_datasource_tls CHECK (tls_mode = 'VERIFY_FULL'),
    CONSTRAINT chk_datasource_pool_max CHECK (pool_max BETWEEN 1 AND 8),
    CONSTRAINT chk_datasource_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT chk_datasource_config_version CHECK (config_version > 0),
    CONSTRAINT chk_datasource_resource_version CHECK (resource_version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE resource_namespace (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    catalog_raw VARCHAR(128) NOT NULL,
    catalog_norm VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    schema_raw VARCHAR(128) NOT NULL,
    schema_norm VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_namespace_identity (
        datasource_id, catalog_norm, schema_norm, generation),
    CONSTRAINT fk_resource_namespace_datasource FOREIGN KEY (datasource_id)
        REFERENCES datasource (id),
    CONSTRAINT chk_resource_namespace_generation CHECK (generation > 0),
    CONSTRAINT chk_resource_namespace_status CHECK (status IN ('ACTIVE', 'MISSING', 'RETIRED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE schema_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    resource_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    object_count INT NOT NULL DEFAULT 0,
    checksum BINARY(32) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_schema_snapshot_datasource_status (datasource_id, status, captured_at),
    CONSTRAINT fk_schema_snapshot_datasource FOREIGN KEY (datasource_id)
        REFERENCES datasource (id),
    CONSTRAINT chk_schema_snapshot_status CHECK (status IN ('STAGING', 'PUBLISHED', 'FAILED', 'RETIRED')),
    CONSTRAINT chk_schema_snapshot_config_version CHECK (config_version > 0),
    CONSTRAINT chk_schema_snapshot_resource_version CHECK (resource_version > 0),
    CONSTRAINT chk_schema_snapshot_object_count CHECK (object_count >= 0),
    CONSTRAINT chk_schema_snapshot_expiry CHECK (expires_at > captured_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE resource_object (
    id BIGINT NOT NULL AUTO_INCREMENT,
    namespace_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    object_raw VARCHAR(128) NOT NULL,
    object_norm VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    quoted TINYINT(1) NOT NULL DEFAULT 0,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    definition_hash BINARY(32) NOT NULL,
    physical_identity VARCHAR(256) NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_object_identity (namespace_id, object_norm, generation),
    CONSTRAINT fk_resource_object_namespace FOREIGN KEY (namespace_id)
        REFERENCES resource_namespace (id),
    CONSTRAINT chk_resource_object_generation CHECK (generation > 0),
    CONSTRAINT chk_resource_object_quoted CHECK (quoted IN (0, 1)),
    CONSTRAINT chk_resource_object_kind CHECK (kind IN ('TABLE', 'VIEW')),
    CONSTRAINT chk_resource_object_status CHECK (status IN ('ACTIVE', 'MISSING', 'RETIRED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE snapshot_object (
    snapshot_id BIGINT NOT NULL,
    object_id BIGINT NOT NULL,
    object_version BIGINT NOT NULL,
    table_comment TEXT NULL,
    column_signature BINARY(32) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, object_id),
    CONSTRAINT fk_snapshot_object_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES schema_snapshot (id),
    CONSTRAINT fk_snapshot_object_object FOREIGN KEY (object_id)
        REFERENCES resource_object (id),
    CONSTRAINT chk_snapshot_object_version CHECK (object_version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE resource_column (
    snapshot_id BIGINT NOT NULL,
    object_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    column_raw VARCHAR(128) NOT NULL,
    column_norm VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    quoted TINYINT(1) NOT NULL DEFAULT 0,
    data_type VARCHAR(128) NOT NULL,
    jdbc_type INT NOT NULL,
    nullable TINYINT(1) NOT NULL,
    precision_value INT NULL,
    scale_value INT NULL,
    max_length BIGINT NULL,
    comment TEXT NULL,
    is_primary_key TINYINT(1) NOT NULL DEFAULT 0,
    foreign_key JSON NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, object_id, ordinal),
    UNIQUE KEY uk_resource_column_name (snapshot_id, object_id, column_norm),
    CONSTRAINT fk_resource_column_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES schema_snapshot (id),
    CONSTRAINT fk_resource_column_object FOREIGN KEY (object_id)
        REFERENCES resource_object (id),
    CONSTRAINT chk_resource_column_ordinal CHECK (ordinal >= 0),
    CONSTRAINT chk_resource_column_quoted CHECK (quoted IN (0, 1)),
    CONSTRAINT chk_resource_column_nullable CHECK (nullable IN (0, 1)),
    CONSTRAINT chk_resource_column_primary CHECK (is_primary_key IN (0, 1)),
    CONSTRAINT chk_resource_column_max_length CHECK (max_length IS NULL OR max_length >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE business_dictionary (
    object_id BIGINT NOT NULL,
    column_norm VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '',
    title VARCHAR(128) NOT NULL,
    description TEXT NULL,
    owner_user_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (object_id, column_norm),
    CONSTRAINT fk_business_dictionary_object FOREIGN KEY (object_id)
        REFERENCES resource_object (id),
    CONSTRAINT chk_business_dictionary_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE platform_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_generation BIGINT NOT NULL,
    action VARCHAR(8) NOT NULL,
    include_descendants TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_scope_target (
        platform_id, resource_type, resource_id, resource_generation, action),
    KEY idx_platform_scope_resource (resource_type, resource_id),
    CONSTRAINT fk_platform_scope_platform FOREIGN KEY (platform_id)
        REFERENCES platform_registry (id),
    CONSTRAINT chk_platform_scope_resource_type CHECK (
        resource_type IN ('DATASOURCE', 'NAMESPACE', 'OBJECT')),
    CONSTRAINT chk_platform_scope_action CHECK (action IN ('META', 'READ')),
    CONSTRAINT chk_platform_scope_descendants CHECK (include_descendants IN (0, 1)),
    CONSTRAINT chk_platform_scope_generation CHECK (resource_generation > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE acl_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    subject_type VARCHAR(16) NOT NULL,
    subject_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_generation BIGINT NOT NULL,
    action VARCHAR(8) NOT NULL,
    effect VARCHAR(8) NOT NULL,
    include_descendants TINYINT(1) NOT NULL DEFAULT 0,
    valid_from DATETIME(6) NOT NULL,
    valid_to DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_acl_grant_subject (subject_type, subject_id, status, valid_from, valid_to),
    KEY idx_acl_grant_resource (resource_type, resource_id, resource_generation, status),
    CONSTRAINT chk_acl_grant_subject_type CHECK (subject_type IN ('USER', 'ROLE', 'DEPT')),
    CONSTRAINT chk_acl_grant_resource_type CHECK (
        resource_type IN ('DATASOURCE', 'NAMESPACE', 'OBJECT')),
    CONSTRAINT chk_acl_grant_action CHECK (action IN ('META', 'READ')),
    CONSTRAINT chk_acl_grant_effect CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT chk_acl_grant_descendants CHECK (include_descendants IN (0, 1)),
    CONSTRAINT chk_acl_grant_window CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT chk_acl_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_acl_grant_generation CHECK (resource_generation > 0),
    CONSTRAINT chk_acl_grant_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE admin_delegation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    department_root_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_generation BIGINT NOT NULL,
    allowed_actions JSON NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_to DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_delegation_user (user_id, status, valid_from, valid_to),
    CONSTRAINT chk_admin_delegation_resource_type CHECK (
        resource_type IN ('DATASOURCE', 'NAMESPACE', 'OBJECT')),
    CONSTRAINT chk_admin_delegation_actions CHECK (JSON_TYPE(allowed_actions) = 'ARRAY'),
    CONSTRAINT chk_admin_delegation_window CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT chk_admin_delegation_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_admin_delegation_generation CHECK (resource_generation > 0),
    CONSTRAINT chk_admin_delegation_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_dept (
    dept_id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NOT NULL DEFAULT 0,
    ancestors VARCHAR(500) NOT NULL DEFAULT '',
    dept_name VARCHAR(30) NOT NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (dept_id),
    KEY idx_sys_dept_parent (parent_id),
    CONSTRAINT chk_sys_dept_status CHECK (status IN ('0', '1')),
    CONSTRAINT chk_sys_dept_del_flag CHECK (del_flag IN ('0', '1'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_role (
    role_id BIGINT NOT NULL AUTO_INCREMENT,
    role_key VARCHAR(100) NOT NULL,
    role_name VARCHAR(30) NOT NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (role_id),
    UNIQUE KEY uk_sys_role_key (role_key),
    CONSTRAINT chk_sys_role_status CHECK (status IN ('0', '1')),
    CONSTRAINT chk_sys_role_del_flag CHECK (del_flag IN ('0', '1'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (role_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_user_login_name (
    user_id BIGINT NOT NULL,
    normalized_name VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_sys_user_login_name (normalized_name),
    CONSTRAINT chk_sys_user_login_name_normalized CHECK (
        normalized_name = LOWER(normalized_name)),
    CONSTRAINT chk_sys_user_login_name_length CHECK (
        CHAR_LENGTH(normalized_name) BETWEEN 1 AND 64)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
