-- Test-only RuoYi identity table. The repository intentionally ships no sys_user DDL because the
-- identity store belongs to the host deployment; authz tests create the minimal contract their
-- JdbcAccountAuthenticator reads.
CREATE TABLE sys_user (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(30) NOT NULL,
    nick_name VARCHAR(30) NOT NULL,
    password VARCHAR(100) NOT NULL,
    status CHAR(1) NOT NULL,
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_sys_user_user_name (user_name),
    CONSTRAINT chk_sys_user_status CHECK (status IN ('0', '1')),
    CONSTRAINT chk_sys_user_del_flag CHECK (del_flag IN ('0', '1'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_bin;
