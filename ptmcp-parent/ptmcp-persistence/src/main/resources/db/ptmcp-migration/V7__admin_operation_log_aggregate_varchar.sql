-- Admin operation log aggregates are not always numeric: OAuth client ids are VARCHAR(100)
-- UUIDs. Widen the aggregate id so client lifecycle entries fit beside the numeric ones.
ALTER TABLE admin_operation_log
    MODIFY COLUMN aggregate_id VARCHAR(64) NOT NULL;
