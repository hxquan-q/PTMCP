-- metadata_sync_job: async schema-sync job state machine (db-design §4/§6).
-- At most one RUNNING job per datasource via a stored generated column: MySQL has no
-- partial unique index, so RUNNING rows carry the datasource id and all other states NULL.
CREATE TABLE metadata_sync_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    datasource_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    snapshot_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    running_guard BIGINT GENERATED ALWAYS AS (
        CASE WHEN state = 'RUNNING' THEN datasource_id ELSE NULL END) STORED,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metadata_sync_job_running_source (running_guard),
    KEY idx_metadata_sync_job_state (state, lease_until, id),
    CONSTRAINT fk_metadata_sync_job_datasource FOREIGN KEY (datasource_id)
        REFERENCES datasource (id),
    CONSTRAINT chk_metadata_sync_job_state
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_metadata_sync_job_config_version CHECK (config_version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
