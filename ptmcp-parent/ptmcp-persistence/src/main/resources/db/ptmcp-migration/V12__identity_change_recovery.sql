-- Immutable recovery material, never returned to the client or used for authentication.
CREATE TABLE identity_password_change (
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    auth_version BIGINT NOT NULL,
    profile_version BIGINT NOT NULL,
    expected_hash BINARY(32) NULL,
    target_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    requested_by BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (operation_id),
    KEY idx_identity_change_user (user_id, status),
    UNIQUE KEY uk_identity_change_profile (user_id, profile_version),
    CONSTRAINT chk_identity_change_status CHECK (status IN ('PENDING','COMPLETED')),
    CONSTRAINT chk_identity_change_material CHECK ((status='PENDING' AND expected_hash IS NOT NULL AND target_hash IS NOT NULL) OR (status='COMPLETED' AND expected_hash IS NULL AND target_hash IS NULL)),
    CONSTRAINT chk_identity_change_versions CHECK (user_id > 0 AND auth_version > 0 AND profile_version > 0),
    CONSTRAINT fk_identity_change_user FOREIGN KEY (user_id) REFERENCES sys_user_security(user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_bin;
