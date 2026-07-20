-- WP3C-B4: audited projection reconciliation, repair, and rollback-safe retired cleanup.
ALTER TABLE rag_index_generation
    ADD COLUMN purged_at DATETIME(3) NULL AFTER previous_generation_id;

ALTER TABLE retrieval_vector_batch
    ADD COLUMN last_reconciled_at DATETIME(3) NULL AFTER state,
    ADD KEY idx_vector_batch_reconcile (index_generation_id, last_reconciled_at);

ALTER TABLE retrieval_chunk_vector_projection
    ADD COLUMN provider_deleted_at DATETIME(3) NULL AFTER indexed_at,
    ADD KEY idx_vector_projection_cleanup (index_generation_id, provider_deleted_at);

CREATE TABLE IF NOT EXISTS rag_projection_repair_audit (
    repair_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    batch_no INT NOT NULL,
    work_key VARCHAR(160) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    missing_ids_fingerprint CHAR(64) NOT NULL,
    missing_count INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    open_slot VARCHAR(192) GENERATED ALWAYS AS (
        CASE WHEN state IN ('QUEUED', 'RUNNING')
             THEN CONCAT(index_generation_id, ':', revision_id, ':', batch_no) ELSE NULL END
    ) STORED,
    PRIMARY KEY (repair_id),
    UNIQUE KEY uk_projection_repair_work (revision_id, work_key),
    UNIQUE KEY uk_projection_repair_open (open_slot),
    KEY idx_projection_repair_state (index_generation_id, state, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_projection_reconciliation_cursor (
    index_generation_id VARCHAR(64) NOT NULL,
    pagination_token VARCHAR(2048) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (index_generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_projection_orphan_deletion_audit (
    deletion_id VARCHAR(64) NOT NULL,
    worker_generation_id VARCHAR(64) NOT NULL,
    vector_ids_fingerprint CHAR(64) NOT NULL,
    vector_count INT NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    PRIMARY KEY (deletion_id),
    KEY idx_orphan_deletion_generation (worker_generation_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_generation_target_repair_audit (
    repair_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    failed_job_id VARCHAR(64) NOT NULL,
    failed_error_code VARCHAR(64) NULL,
    requested_by_hash CHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    PRIMARY KEY (repair_id),
    KEY idx_target_repair_target (index_generation_id, revision_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
