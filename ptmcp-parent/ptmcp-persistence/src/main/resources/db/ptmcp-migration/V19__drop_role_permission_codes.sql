-- S3 (DEV-20261104-permission-simplification): drop the capability-code table.
--
-- Rationale: with the single-admin model (S1 column, S2 binary gate) no code decides management
-- authorization any more, and roles are pure data templates (S3 narrowed RoleWrite/RoleRead to
-- key/name/enabled). sys_role_permission (V5) was both the action-code source and the platform
-- admin source; both consumers were re-pointed before this drop (V18 backfilled the admin flag).
-- The data-permission path (授权矩阵 acl_grant + 角色->用户继承) never read this table and is
-- unaffected.
DROP TABLE sys_role_permission;
