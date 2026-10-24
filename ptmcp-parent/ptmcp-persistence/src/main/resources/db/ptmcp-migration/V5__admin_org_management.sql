-- V5: management API support tables. Appends to the V2 org adaptation tables without altering
-- frozen V1-V4 semantics: version columns for If-Match, the per-role permission code list and the
-- PTMCP user profile that carries fields the RuoYi-compatible base tables do not own.
ALTER TABLE sys_dept
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD CONSTRAINT chk_sys_dept_version CHECK (version > 0);

ALTER TABLE sys_role
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD CONSTRAINT chk_sys_role_version CHECK (version > 0);

CREATE TABLE sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (role_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE sys_user_profile (
    user_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    email VARCHAR(50) NULL,
    phone_number VARCHAR(11) NULL,
    remark VARCHAR(500) NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_sys_user_profile_dept (dept_id),
    CONSTRAINT fk_sys_user_profile_dept FOREIGN KEY (dept_id) REFERENCES sys_dept (dept_id),
    CONSTRAINT chk_sys_user_profile_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_sys_user_profile_version CHECK (version > 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
