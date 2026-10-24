-- metadata_sync_job also backs inline datasource connectivity probes (T15-b): the REST surface
-- exposes one JobDTO for both, so the row must carry a stable type. Existing rows are SYNC.
ALTER TABLE metadata_sync_job
    ADD COLUMN job_type VARCHAR(16) NOT NULL DEFAULT 'SYNC' AFTER datasource_id,
    ADD CONSTRAINT chk_metadata_sync_job_type CHECK (job_type IN ('SYNC', 'TEST'));
