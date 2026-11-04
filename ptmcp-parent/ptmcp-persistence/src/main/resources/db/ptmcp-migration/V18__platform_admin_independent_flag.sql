-- S1 (DEV-20261104-permission-simplification): platform administrator becomes an independent
-- boolean column, decoupled from the sys_role_permission capability-code role.
--
-- Rationale: in the single-admin model the platform administrator needs no ptmcp:* capability
-- codes. Today that distilled role is expressed through a synthetic code ('ptmcp:platform:scope')
-- stored on the shared sys_role_permission table, which makes "is this the platform admin" a
-- byproduct of role membership and couples the identity to capability coding. Cut that knot:
-- platform_admin lives on the authoritative user-security row and no longer derives from roles.
--
-- This release builds on top of V13 __unbound_user_identity.sql; the identity_state default is
-- already UNBOUND there. We only add the column; existing READY admins keep their state.

ALTER TABLE sys_user_security
    ADD COLUMN platform_admin TINYINT(1) NOT NULL DEFAULT 0 AFTER identity_state,
    ADD CONSTRAINT chk_user_security_platform CHECK (platform_admin IN (0, 1));

-- Backfill the distilled admin identity from the legacy capability-code role. Users who currently
-- hold 'ptmcp:platform:scope' through an enabled role migrate to the new independent column so the
-- upgrade does not strand the existing platform administrator (the console must stay reachable).
-- This runs before S3 drops sys_role_permission, so the legacy source still exists here.
UPDATE sys_user_security s
JOIN (
    SELECT DISTINCT ur.user_id
    FROM sys_user_role ur
    JOIN sys_role r ON r.role_id = ur.role_id
        AND r.status = '0' AND r.del_flag = '0'
    JOIN sys_role_permission rp ON rp.role_id = r.role_id
    WHERE rp.permission_code = 'ptmcp:platform:scope'
) t ON t.user_id = s.user_id
SET s.platform_admin = 1;