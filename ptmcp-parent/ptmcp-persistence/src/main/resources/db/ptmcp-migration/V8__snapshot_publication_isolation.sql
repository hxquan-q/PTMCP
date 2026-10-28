-- Stop gateways, admin and metadata workers before V8; old workers must not restart.
-- V7 staging could mutate live identities before publication. A source with any abandoned
-- capture is conservatively invalidated, even when that capture predates its active snapshot.
ALTER TABLE resource_namespace
    DROP CHECK chk_resource_namespace_status,
    ADD CONSTRAINT chk_resource_namespace_status
        CHECK (status IN ('STAGING', 'ACTIVE', 'MISSING', 'RETIRED'));

ALTER TABLE resource_object
    DROP CHECK chk_resource_object_status,
    ADD CONSTRAINT chk_resource_object_status
        CHECK (status IN ('STAGING', 'ACTIVE', 'MISSING', 'RETIRED'));

ALTER TABLE snapshot_object
    ADD COLUMN definition_hash BINARY(32) NULL,
    ADD COLUMN physical_identity VARCHAR(256) NULL;

-- Historical definitions cannot be reconstructed from the mutable live row. Keep NULL;
-- only a new, complete capture may publish. No historical snapshot or column data is deleted.
CREATE TEMPORARY TABLE snapshot_upgrade_sources (datasource_id BIGINT NOT NULL PRIMARY KEY);
INSERT INTO snapshot_upgrade_sources (datasource_id)
SELECT DISTINCT datasource_id FROM schema_snapshot WHERE status IN ('STAGING', 'FAILED');

START TRANSACTION;
SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1 FOR UPDATE;

UPDATE resource_object ro
JOIN resource_namespace ns ON ns.id = ro.namespace_id
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ns.datasource_id
SET ro.status = 'RETIRED', ro.update_time = UTC_TIMESTAMP(6)
WHERE ro.status = 'ACTIVE';

UPDATE resource_namespace ns
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ns.datasource_id
SET ns.status = 'RETIRED', ns.update_time = UTC_TIMESTAMP(6)
WHERE ns.status = 'ACTIVE';

UPDATE acl_grant g
JOIN resource_object ro ON g.resource_type = 'OBJECT' AND g.resource_id = ro.id
JOIN resource_namespace ns ON ns.id = ro.namespace_id
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ns.datasource_id
SET g.status = 'REVOKED', g.reason = 'SNAPSHOT_UPGRADE', g.version = g.version + 1,
    g.update_time = UTC_TIMESTAMP(6)
WHERE g.status = 'ACTIVE';

UPDATE acl_grant g
JOIN resource_namespace ns ON g.resource_type = 'NAMESPACE' AND g.resource_id = ns.id
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ns.datasource_id
SET g.status = 'REVOKED', g.reason = 'SNAPSHOT_UPGRADE', g.version = g.version + 1,
    g.update_time = UTC_TIMESTAMP(6)
WHERE g.status = 'ACTIVE';

UPDATE datasource ds
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ds.id
SET ds.active_snapshot_id = NULL, ds.resource_version = ds.resource_version + 1,
    ds.update_time = UTC_TIMESTAMP(6);

UPDATE schema_snapshot ss
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ss.datasource_id
SET ss.status = 'RETIRED', ss.update_time = UTC_TIMESTAMP(6)
WHERE ss.status = 'PUBLISHED';

UPDATE schema_snapshot
SET status = 'FAILED', update_time = UTC_TIMESTAMP(6)
WHERE status = 'STAGING';

UPDATE sys_security_state
SET policy_epoch = policy_epoch + 1, update_time = UTC_TIMESTAMP(6)
WHERE singleton_id = 1 AND EXISTS (SELECT 1 FROM snapshot_upgrade_sources);

INSERT INTO sys_security_event (event_id, epoch, aggregate_type, aggregate_id, event_type,
    payload, occurred_at, create_time, update_time)
SELECT UUID(), security.policy_epoch, 'ACL', CAST(ds.id AS CHAR), 'ACL_CHANGED',
    JSON_OBJECT('schemaVersion', 1, 'aggregateId', CAST(ds.id AS CHAR), 'version', ds.resource_version),
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM datasource ds
JOIN snapshot_upgrade_sources affected ON affected.datasource_id = ds.id
JOIN sys_security_state security ON security.singleton_id = 1;
COMMIT;
DROP TEMPORARY TABLE snapshot_upgrade_sources;
