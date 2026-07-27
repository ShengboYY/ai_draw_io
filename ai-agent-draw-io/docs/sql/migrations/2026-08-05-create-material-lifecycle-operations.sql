-- WP4-C durable lifecycle requests, lease uniqueness, deletion fencing, and deletion proof.
USE ai_draw_io;

ALTER TABLE material
    ADD KEY idx_material_trash_expiry (lifecycle_state, trash_expires_at);

-- A content-free DELETED material is the only temporary state allowed to clear its origin and expiry.
ALTER TABLE material
    DROP CHECK chk_material_retention,
    ADD CONSTRAINT chk_material_retention CHECK (
        (retention_class = 'TEMPORARY' AND (
            (lifecycle_state = 'DELETED' AND origin_conversation_id IS NULL AND expires_at IS NULL)
            OR (origin_conversation_id IS NOT NULL
                AND (lifecycle_state <> 'ACTIVE' OR expires_at IS NOT NULL))))
        OR (retention_class = 'RETAINED' AND expires_at IS NULL)
    );

ALTER TABLE evidence_read_lease
    ADD UNIQUE KEY uk_evidence_lease_run_source (owner_key, run_id, version_id, revision_id);

ALTER TABLE deletion_task
    ADD COLUMN lifecycle_generation BIGINT NOT NULL DEFAULT 0 AFTER version_id,
    ADD COLUMN attempt INT NOT NULL DEFAULT 0 AFTER status,
    ADD UNIQUE KEY uk_deletion_task_material (material_id);

CREATE TABLE material_lifecycle_request (
    owner_key VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    retention_class VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    lifecycle_generation BIGINT NOT NULL,
    expires_at DATETIME(3) NULL,
    trash_expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, material_id, action, request_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_deletion_proof (
    material_id VARCHAR(64) NOT NULL,
    lifecycle_generation BIGINT NOT NULL,
    tombstoned_citation_count BIGINT NOT NULL DEFAULT 0,
    deleted_vector_count BIGINT NOT NULL DEFAULT 0,
    deleted_object_count BIGINT NOT NULL DEFAULT 0,
    vectors_deleted_at DATETIME(3) NULL,
    objects_deleted_at DATETIME(3) NULL,
    database_purged_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    request_ids_hash CHAR(64) NULL,
    error_free TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
