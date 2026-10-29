-- A metadata ID is not proof that the host account with that ID belongs to this identity.
-- Existing READY and recovery operations retain their state and material during upgrade.
ALTER TABLE sys_user_security
    ALTER COLUMN identity_state SET DEFAULT 'UNBOUND',
    DROP CHECK chk_user_security_state,
    DROP CHECK chk_user_security_change,
    ADD CONSTRAINT chk_user_security_state
        CHECK (identity_state IN ('UNBOUND', 'READY', 'CHANGE_PENDING')),
    ADD CONSTRAINT chk_user_security_change CHECK (
        (identity_state IN ('UNBOUND', 'READY') AND identity_change_id IS NULL)
        OR (identity_state = 'CHANGE_PENDING' AND identity_change_id IS NOT NULL
            AND CHAR_LENGTH(identity_change_id) = 36 AND IS_UUID(identity_change_id)));
