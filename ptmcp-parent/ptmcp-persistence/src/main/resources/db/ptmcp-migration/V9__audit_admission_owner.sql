-- Metadata and rejected tool calls have no approved business datasource yet.
-- The nullable reference is filled only after guard and authorization succeed.
ALTER TABLE execution_lease MODIFY datasource_id BIGINT NULL;
