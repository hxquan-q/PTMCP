-- PTMCP metadata only, MySQL 8.4 / InnoDB / utf8mb4, UTC DATETIME(6), no ON UPDATE.
-- OAuth tables start from the official schema shipped in Spring Security 7.1.2
-- (spring-security-oauth2-authorization-server-7.1.2.jar):
--   oauth2-registered-client-schema.sql, oauth2-authorization-schema.sql,
--   oauth2-authorization-consent-schema.sql.
-- Official columns are preserved; PTMCP hashes/family/status/version fields are additive
-- extension columns. Raw code/token blobs stay NULL and are guarded by CHECK constraints:
-- the custom services store only hashes and never use the stock JdbcOAuth2*Service layouts.
-- Audit and idempotency tables are append-only, never partitioned, and carry no update_time.
-- Versioned release unit, not transactional DDL. No credentials, tokens or seed accounts.

CREATE TABLE oauth2_registered_client (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    client_id_issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    client_secret VARCHAR(200) NULL,
    client_secret_expires_at DATETIME(6) NULL,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(1000) NULL,
    post_logout_redirect_uris VARCHAR(1000) NULL,
    scopes VARCHAR(1000) NOT NULL,
    client_settings VARCHAR(2000) NOT NULL,
    token_settings VARCHAR(2000) NOT NULL,
    platform_id BIGINT NULL,
    client_type VARCHAR(16) NOT NULL,
    client_secret_hash BINARY(32) NULL,
    jwks_uri VARCHAR(2048) NULL,
    metadata_uri VARCHAR(2048) NULL,
    metadata_digest BINARY(32) NULL,
    status VARCHAR(16) NOT NULL,
    registration_source VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth2_registered_client_client_id (client_id),
    CONSTRAINT fk_oauth2_registered_client_platform FOREIGN KEY (platform_id)
        REFERENCES platform_registry (id),
    CONSTRAINT chk_oauth2_registered_client_type CHECK (
        client_type IN ('PUBLIC', 'CONFIDENTIAL')),
    CONSTRAINT chk_oauth2_registered_client_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_oauth2_registered_client_source CHECK (
        registration_source IN ('ADMIN', 'DCR', 'CIMD')),
    CONSTRAINT chk_oauth2_registered_client_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE oauth2_authorization (
    id VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000) NULL,
    attributes BLOB NULL,
    state VARCHAR(500) NULL,
    authorization_code_value BLOB NULL,
    authorization_code_issued_at DATETIME(6) NULL,
    authorization_code_expires_at DATETIME(6) NULL,
    authorization_code_metadata BLOB NULL,
    access_token_value BLOB NULL,
    access_token_issued_at DATETIME(6) NULL,
    access_token_expires_at DATETIME(6) NULL,
    access_token_metadata BLOB NULL,
    access_token_type VARCHAR(100) NULL,
    access_token_scopes VARCHAR(1000) NULL,
    oidc_id_token_value BLOB NULL,
    oidc_id_token_issued_at DATETIME(6) NULL,
    oidc_id_token_expires_at DATETIME(6) NULL,
    oidc_id_token_metadata BLOB NULL,
    refresh_token_value BLOB NULL,
    refresh_token_issued_at DATETIME(6) NULL,
    refresh_token_expires_at DATETIME(6) NULL,
    refresh_token_metadata BLOB NULL,
    user_code_value BLOB NULL,
    user_code_issued_at DATETIME(6) NULL,
    user_code_expires_at DATETIME(6) NULL,
    user_code_metadata BLOB NULL,
    device_code_value BLOB NULL,
    device_code_issued_at DATETIME(6) NULL,
    device_code_expires_at DATETIME(6) NULL,
    device_code_metadata BLOB NULL,
    user_id BIGINT NULL,
    family_id CHAR(36) NULL,
    authorization_code_hash BINARY(32) NULL,
    code_expires_at DATETIME(6) NULL,
    code_consumed_at DATETIME(6) NULL,
    redirect_uri VARCHAR(2048) NULL,
    pkce_challenge VARCHAR(128) NULL,
    pkce_method VARCHAR(16) NULL,
    scopes JSON NULL,
    auth_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    expires_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth2_authorization_code_hash (authorization_code_hash),
    KEY idx_oauth2_authorization_family (family_id, status),
    KEY idx_oauth2_authorization_user (user_id, create_time),
    CONSTRAINT fk_oauth2_authorization_client FOREIGN KEY (registered_client_id)
        REFERENCES oauth2_registered_client (id),
    CONSTRAINT chk_oauth2_authorization_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_oauth2_authorization_auth_version CHECK (auth_version > 0),
    CONSTRAINT chk_oauth2_authorization_version CHECK (version > 0),
    CONSTRAINT chk_oauth2_authorization_pkce CHECK (
        pkce_method IS NULL OR pkce_method IN ('S256', 'PLAIN')),
    CONSTRAINT chk_oauth2_authorization_code_window CHECK (
        code_expires_at IS NULL OR code_consumed_at IS NULL OR code_consumed_at >= create_time),
    CONSTRAINT chk_oauth2_authorization_no_raw_code CHECK (authorization_code_value IS NULL),
    CONSTRAINT chk_oauth2_authorization_no_raw_access CHECK (access_token_value IS NULL),
    CONSTRAINT chk_oauth2_authorization_no_raw_id_token CHECK (oidc_id_token_value IS NULL),
    CONSTRAINT chk_oauth2_authorization_no_raw_refresh CHECK (refresh_token_value IS NULL),
    CONSTRAINT chk_oauth2_authorization_no_raw_user_code CHECK (user_code_value IS NULL),
    CONSTRAINT chk_oauth2_authorization_no_raw_device_code CHECK (device_code_value IS NULL)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities VARCHAR(1000) NOT NULL,
    user_id BIGINT NULL,
    scopes JSON NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name),
    CONSTRAINT fk_oauth2_authorization_consent_client FOREIGN KEY (registered_client_id)
        REFERENCES oauth2_registered_client (id),
    CONSTRAINT chk_oauth2_authorization_consent_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE oauth_refresh_family (
    id CHAR(36) NOT NULL,
    authorization_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    platform_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    auth_version BIGINT NOT NULL,
    absolute_expires_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    last_rotated_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoke_reason VARCHAR(200) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_oauth_refresh_family_user (user_id, status),
    KEY idx_oauth_refresh_family_expiry (status, absolute_expires_at, idle_expires_at),
    CONSTRAINT fk_oauth_refresh_family_authorization FOREIGN KEY (authorization_id)
        REFERENCES oauth2_authorization (id),
    CONSTRAINT fk_oauth_refresh_family_client FOREIGN KEY (registered_client_id)
        REFERENCES oauth2_registered_client (id),
    CONSTRAINT fk_oauth_refresh_family_platform FOREIGN KEY (platform_id)
        REFERENCES platform_registry (id),
    CONSTRAINT chk_oauth_refresh_family_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_oauth_refresh_family_auth_version CHECK (auth_version > 0),
    CONSTRAINT chk_oauth_refresh_family_version CHECK (version > 0),
    CONSTRAINT chk_oauth_refresh_family_window CHECK (
        idle_expires_at <= absolute_expires_at),
    CONSTRAINT chk_oauth_refresh_family_revoked CHECK (
        status <> 'REVOKED' OR revoked_at IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE oauth_refresh_token (
    token_hash BINARY(32) NOT NULL,
    family_id CHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    successor_hash BINARY(32) NULL,
    status VARCHAR(16) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (token_hash),
    UNIQUE KEY uk_oauth_refresh_token_generation (family_id, generation),
    KEY idx_oauth_refresh_token_family (family_id, status),
    CONSTRAINT fk_oauth_refresh_token_family FOREIGN KEY (family_id)
        REFERENCES oauth_refresh_family (id),
    CONSTRAINT chk_oauth_refresh_token_generation CHECK (generation > 0),
    CONSTRAINT chk_oauth_refresh_token_expiry CHECK (expires_at > issued_at),
    CONSTRAINT chk_oauth_refresh_token_status CHECK (
        status IN ('ACTIVE', 'CONSUMED', 'REVOKED')),
    CONSTRAINT chk_oauth_refresh_token_consumed CHECK (
        status <> 'CONSUMED' OR consumed_at IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE personal_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    platform_id BIGINT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    scopes JSON NOT NULL,
    auth_version BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_personal_token_hash (token_hash),
    KEY idx_personal_token_user (user_id, status, create_time),
    CONSTRAINT fk_personal_token_platform FOREIGN KEY (platform_id)
        REFERENCES platform_registry (id),
    CONSTRAINT chk_personal_token_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_personal_token_auth_version CHECK (auth_version > 0),
    CONSTRAINT chk_personal_token_version CHECK (version > 0),
    CONSTRAINT chk_personal_token_expiry CHECK (expires_at > create_time),
    CONSTRAINT chk_personal_token_prefix CHECK (CHAR_LENGTH(token_prefix) > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE web_session_index (
    session_hash BINARY(32) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    user_id BIGINT NOT NULL,
    auth_version BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (session_hash),
    KEY idx_web_session_index_user (user_id, kind),
    CONSTRAINT chk_web_session_index_kind CHECK (kind IN ('ADMIN', 'AS')),
    CONSTRAINT chk_web_session_index_auth_version CHECK (auth_version > 0),
    CONSTRAINT chk_web_session_index_version CHECK (version > 0),
    CONSTRAINT chk_web_session_index_expiry CHECK (expires_at > issued_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE execution_lease (
    request_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    gateway_instance_id VARCHAR(128) NOT NULL,
    boot_id CHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL,
    execution_started_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    KEY idx_execution_lease_owner (
        gateway_instance_id, boot_id, state, lease_until),
    CONSTRAINT fk_execution_lease_datasource FOREIGN KEY (datasource_id)
        REFERENCES datasource (id),
    CONSTRAINT chk_execution_lease_state CHECK (
        state IN ('QUEUED', 'RUNNING', 'CANCELLING', 'TERMINAL')),
    CONSTRAINT chk_execution_lease_version CHECK (version > 0),
    CONSTRAINT chk_execution_lease_heartbeat CHECK (lease_until >= heartbeat_at),
    CONSTRAINT chk_execution_lease_started CHECK (
        execution_started_at IS NULL OR execution_started_at <= heartbeat_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE audit_outbox (
    event_id CHAR(36) NOT NULL,
    request_id CHAR(36) NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    seq TINYINT NOT NULL,
    event_payload MEDIUMBLOB NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_audit_outbox_request_kind (request_id, event_kind),
    KEY idx_audit_outbox_publish (published_at, create_time, event_id),
    CONSTRAINT chk_audit_outbox_kind CHECK (event_kind IN ('START', 'TERMINAL')),
    CONSTRAINT chk_audit_outbox_seq CHECK (
        (event_kind = 'START' AND seq = 0)
        OR (event_kind = 'TERMINAL' AND seq = 1)),
    CONSTRAINT chk_audit_outbox_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE audit_log (
    request_id CHAR(36) NOT NULL,
    user_id BIGINT NULL,
    platform_id BIGINT NULL,
    tool VARCHAR(64) NOT NULL,
    datasource_id BIGINT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    result VARCHAR(16) NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    sql_masked MEDIUMTEXT NULL,
    sql_fingerprint BINARY(32) NULL,
    sql_raw_cipher MEDIUMBLOB NULL,
    sql_final_cipher MEDIUMBLOB NULL,
    key_id VARCHAR(128) NULL,
    raw_length INT NULL,
    resource_refs JSON NULL,
    versions JSON NULL,
    duration_ms BIGINT NULL,
    queue_ms INT NULL,
    db_ms INT NULL,
    rows_returned INT NULL,
    response_bytes INT NULL,
    truncated TINYINT(1) NOT NULL DEFAULT 0,
    client_ip VARBINARY(16) NULL,
    create_time DATETIME(6) NOT NULL,
    terminal_hash BINARY(32) NULL,
    PRIMARY KEY (request_id),
    KEY idx_audit_log_started (started_at, request_id),
    KEY idx_audit_log_user (user_id, started_at, request_id),
    KEY idx_audit_log_platform (platform_id, started_at, request_id),
    KEY idx_audit_log_result (result, started_at, request_id),
    CONSTRAINT chk_audit_log_result CHECK (
        result IN ('RUNNING', 'SUCCESS', 'ERROR', 'UNKNOWN')),
    CONSTRAINT chk_audit_log_verdict CHECK (
        verdict IN ('ALLOW', 'DENY', 'ERROR', 'UNKNOWN')),
    CONSTRAINT chk_audit_log_truncated CHECK (truncated IN (0, 1)),
    CONSTRAINT chk_audit_log_completed CHECK (
        completed_at IS NULL OR completed_at >= started_at),
    CONSTRAINT chk_audit_log_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT chk_audit_log_queue CHECK (queue_ms IS NULL OR queue_ms >= 0),
    CONSTRAINT chk_audit_log_db CHECK (db_ms IS NULL OR db_ms >= 0),
    CONSTRAINT chk_audit_log_rows CHECK (rows_returned IS NULL OR rows_returned >= 0),
    CONSTRAINT chk_audit_log_response CHECK (
        response_bytes IS NULL OR response_bytes >= 0),
    CONSTRAINT chk_audit_log_raw_length CHECK (raw_length IS NULL OR raw_length >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE audit_sensitive_access (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id CHAR(36) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    fields JSON NOT NULL,
    decision VARCHAR(8) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_sensitive_access_request (request_id, create_time),
    CONSTRAINT chk_audit_sensitive_access_decision CHECK (decision IN ('ALLOW', 'DENY')),
    CONSTRAINT chk_audit_sensitive_access_reason CHECK (CHAR_LENGTH(reason) > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE api_idempotency (
    actor_id BIGINT NOT NULL,
    route_key VARCHAR(128) NOT NULL,
    key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    state VARCHAR(16) NOT NULL,
    resource_id BIGINT NULL,
    response_cipher MEDIUMBLOB NULL,
    http_status INT NULL,
    create_time DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id, route_key, key_hash),
    KEY idx_api_idempotency_expiry (expires_at),
    CONSTRAINT chk_api_idempotency_state CHECK (
        state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_api_idempotency_expiry CHECK (expires_at > create_time),
    CONSTRAINT chk_api_idempotency_status CHECK (
        http_status IS NULL OR http_status BETWEEN 100 AND 599)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE admin_operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    action VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    before_masked JSON NULL,
    after_masked JSON NULL,
    occurred_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_operation_log_actor (actor_id, occurred_at),
    CONSTRAINT chk_admin_operation_log_action CHECK (CHAR_LENGTH(action) > 0),
    CONSTRAINT chk_admin_operation_log_aggregate CHECK (CHAR_LENGTH(aggregate_type) > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
