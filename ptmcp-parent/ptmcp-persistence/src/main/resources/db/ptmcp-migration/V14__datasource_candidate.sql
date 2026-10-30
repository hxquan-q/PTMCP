-- Candidates are immutable probe results. Publishing copies the verified row into the active
-- datasource in one security transaction; only lifecycle state changes on the candidate history.
CREATE TABLE datasource_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    base_config_version BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    engine VARCHAR(16) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(128) NOT NULL,
    username VARCHAR(128) NOT NULL,
    credential_cipher BLOB NULL,
    key_id VARCHAR(128) NULL,
    tls_mode VARCHAR(16) NOT NULL,
    pool_max SMALLINT NOT NULL,
    datasource_status VARCHAR(16) NOT NULL,
    probe_scope VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    error_code VARCHAR(64) NULL,
    requested_by BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_datasource_candidate_health
        (datasource_id, probe_scope, base_config_version, state, valid_until, checked_at, id),
    CONSTRAINT fk_datasource_candidate_datasource
        FOREIGN KEY (datasource_id) REFERENCES datasource (id),
    CONSTRAINT chk_datasource_candidate_base_version CHECK (base_config_version > 0),
    CONSTRAINT chk_datasource_candidate_engine
        CHECK (engine IN ('MYSQL', 'POSTGRESQL')),
    CONSTRAINT chk_datasource_candidate_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT chk_datasource_candidate_tls CHECK (tls_mode = 'VERIFY_FULL'),
    CONSTRAINT chk_datasource_candidate_pool_max CHECK (pool_max BETWEEN 1 AND 8),
    CONSTRAINT chk_datasource_candidate_status
        CHECK (datasource_status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT chk_datasource_candidate_scope
        CHECK (probe_scope IN ('ACTIVE_PROBE', 'DRAFT_CANDIDATE')),
    CONSTRAINT chk_datasource_candidate_state
        CHECK (state IN ('VERIFIED', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_datasource_candidate_window CHECK (valid_until > checked_at),
    CONSTRAINT chk_datasource_candidate_secret_pair
        CHECK ((credential_cipher IS NULL AND key_id IS NULL)
            OR (credential_cipher IS NOT NULL AND key_id IS NOT NULL)),
    CONSTRAINT chk_datasource_candidate_secret_state
        CHECK ((state = 'FAILED' AND credential_cipher IS NULL)
            OR (state IN ('VERIFIED', 'PUBLISHED') AND credential_cipher IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

ALTER TABLE metadata_sync_job
    ADD COLUMN candidate_id BIGINT NULL AFTER config_version,
    ADD CONSTRAINT fk_metadata_sync_job_candidate FOREIGN KEY (candidate_id)
        REFERENCES datasource_candidate (id);
