-- PTMCP metadata only. Sessions and producers must use UTC.
-- Versioned release unit, not transactional DDL: MySQL implicitly commits DDL.
-- No accounts, credentials, business fixtures, destructive clean or down migration.
CREATE TABLE sys_security_state (
    singleton_id TINYINT NOT NULL,
    policy_epoch BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT chk_security_state_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_security_state_epoch CHECK (policy_epoch > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_security_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_security_event_epoch_event_id (epoch, event_id),
    KEY idx_security_event_published (published_at, occurred_at, event_id),
    CONSTRAINT chk_security_event_uuid CHECK (CHAR_LENGTH(event_id) = 36 AND IS_UUID(event_id)),
    CONSTRAINT chk_security_event_epoch CHECK (epoch > 0),
    CONSTRAINT chk_security_event_payload CHECK (JSON_TYPE(payload) = 'OBJECT'),
    CONSTRAINT chk_security_event_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Zero denotes a consumer that has not processed an epoch yet.
CREATE TABLE security_consumer_cursor (
    consumer_id VARCHAR(128) NOT NULL,
    last_epoch BIGINT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_id),
    CONSTRAINT chk_security_cursor_epoch CHECK (last_epoch >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO sys_security_state (singleton_id, policy_epoch, create_time, update_time)
VALUES (1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
