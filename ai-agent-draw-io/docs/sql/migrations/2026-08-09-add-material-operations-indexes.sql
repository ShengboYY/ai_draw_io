-- WP8 bounded operational aggregates. Apply once after the 2026-08-08 migration.
ALTER TABLE material_processing_job
    ADD INDEX idx_processing_job_status_updated (status, updated_at);

ALTER TABLE evidence_read_lease
    ADD INDEX idx_evidence_lease_status_expiry (status, expires_at);

ALTER TABLE material
    ADD INDEX idx_material_lifecycle_updated (lifecycle_state, updated_at);

ALTER TABLE material_processing_usage
    ADD INDEX idx_processing_usage_recorded (recorded_at);

ALTER TABLE retrieval_vector_batch
    ADD INDEX idx_vector_batch_state_reconciled (state, last_reconciled_at);

ALTER TABLE rag_projection_repair_audit
    ADD INDEX idx_projection_repair_global_state (state, requested_at);

ALTER TABLE rag_projection_orphan_deletion_audit
    ADD INDEX idx_orphan_deletion_completion (completed_at, requested_at);

CREATE TABLE IF NOT EXISTS material_provider_capacity_snapshot (
    singleton_key TINYINT NOT NULL,
    captured_at DATETIME(3) NOT NULL,
    sequence_no BIGINT NOT NULL,
    embedding_percent DECIMAL(7,4) NOT NULL,
    vector_read_percent DECIMAL(7,4) NOT NULL,
    vector_write_percent DECIMAL(7,4) NOT NULL,
    dependencies_available BOOLEAN NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (singleton_key),
    CHECK (singleton_key = 1),
    CHECK (sequence_no &gt;= 0),
    CHECK (embedding_percent BETWEEN 0 AND 100),
    CHECK (vector_read_percent BETWEEN 0 AND 100),
    CHECK (vector_write_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
