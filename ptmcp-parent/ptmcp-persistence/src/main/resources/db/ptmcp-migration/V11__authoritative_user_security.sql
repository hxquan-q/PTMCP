-- Stop all legacy writers before applying this release; mixed-version traffic is unsupported.
-- Identity base tables remain owned by the host. This is metadata, never a password store.
CREATE TABLE sys_user_security (
    user_id BIGINT NOT NULL,
    auth_version BIGINT NOT NULL DEFAULT 1,
    identity_version BIGINT NOT NULL DEFAULT 1,
    identity_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    identity_change_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    password_changed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT chk_user_security_versions CHECK (
        user_id > 0 AND auth_version > 0 AND identity_version > 0 AND version > 0),
    CONSTRAINT chk_user_security_state CHECK (identity_state IN ('READY', 'CHANGE_PENDING')),
    CONSTRAINT chk_user_security_change CHECK (
        (identity_state = 'READY' AND identity_change_id IS NULL)
        OR (identity_state = 'CHANGE_PENDING' AND identity_change_id IS NOT NULL
            AND CHAR_LENGTH(identity_change_id) = 36 AND IS_UUID(identity_change_id)))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Existing credentials are intentionally invalidated, including orphaned historical subjects.
-- MAX comes only from credentials for migration; it is never a runtime authority.
INSERT INTO sys_user_security (user_id, auth_version, identity_version, identity_state,
    version, create_time, update_time)
SELECT user_id, GREATEST(1, MAX(credential_version) + 1), 1, 'READY', 1,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM (
    SELECT user_id, 0 AS credential_version FROM sys_user_login_name
    UNION ALL SELECT user_id, 0 FROM sys_user_profile
    UNION ALL SELECT user_id, auth_version FROM personal_token
    UNION ALL SELECT user_id, auth_version FROM oauth_refresh_family
    UNION ALL SELECT user_id, auth_version FROM web_session_index
    UNION ALL SELECT user_id, COALESCE(auth_version, 0) FROM oauth2_authorization
        WHERE user_id IS NOT NULL
) subjects
GROUP BY user_id;
