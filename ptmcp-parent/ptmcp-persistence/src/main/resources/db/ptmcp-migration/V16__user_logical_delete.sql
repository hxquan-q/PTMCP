-- User logical delete (api-spec /users CRUD): status gains 'DELETED'; the row stays for
-- audit, disappears from management lists, and every credential surface is revoked.
ALTER TABLE sys_user_profile DROP CHECK chk_sys_user_profile_status;
ALTER TABLE sys_user_profile ADD CONSTRAINT chk_sys_user_profile_status CHECK (
    status IN ('ACTIVE', 'DISABLED', 'DELETED'));
