-- Development-only identity table for the local OAuth authorization server.
-- Production deployments own sys_user through their host identity store; this file
-- must never be applied to a production metadata database.
CREATE TABLE IF NOT EXISTS sys_user (
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

INSERT INTO sys_user (user_id, user_name, nick_name, password, status, del_flag)
SELECT user_id, 'admin', display_name, password_hash, '0', '0'
FROM sys_user_profile
WHERE user_id = 1
ON DUPLICATE KEY UPDATE
    nick_name = VALUES(nick_name),
    password = VALUES(password),
    status = '0',
    del_flag = '0';
