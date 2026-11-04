-- First-credential provisioning for an UNBOUND metadata identity.
-- The host row is created only from this immutable administrator intent and never adopts an
-- existing host account. The operation is kept as a tombstone after completion.
CREATE TABLE identity_provision (
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    auth_version BIGINT NOT NULL,
    profile_version BIGINT NOT NULL,
    target_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    requested_by BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (operation_id),
    UNIQUE KEY uk_identity_provision_user (user_id),
    CONSTRAINT chk_identity_provision_status CHECK (status IN ('PENDING','COMPLETED')),
    CONSTRAINT chk_identity_provision_material CHECK (
        (status = 'PENDING'
            AND target_hash IS NOT NULL
            AND CHAR_LENGTH(target_hash) BETWEEN 20 AND 100)
        OR (status = 'COMPLETED' AND target_hash IS NULL)),
    CONSTRAINT chk_identity_provision_versions CHECK (
        user_id > 0 AND auth_version > 0 AND profile_version > 0 AND requested_by > 0),
    CONSTRAINT fk_identity_provision_user FOREIGN KEY (user_id)
        REFERENCES sys_user_security (user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_bin;
