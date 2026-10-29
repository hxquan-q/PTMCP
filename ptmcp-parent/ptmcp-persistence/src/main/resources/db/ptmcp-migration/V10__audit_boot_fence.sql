-- Monotonic process retirement. Tombstones must outlive the local boot marker.
-- Upgrade all gateway writers together; legacy writers do not consult this fence.
CREATE TABLE audit_boot_owner (
    gateway_instance_id VARCHAR(128) NOT NULL,
    boot_id CHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (gateway_instance_id, boot_id),
    CONSTRAINT chk_audit_boot_owner_state CHECK (state IN ('OPEN', 'RETIRED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
