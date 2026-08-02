-- Auto Memory v1.4-B: durable, rebuildable vector projection work.
-- MySQL remains authoritative; this table stores only work state and opaque vector identities.
USE ai_draw_io;

CREATE TABLE IF NOT EXISTS memory_vector_projection_work (
    memory_id              VARCHAR(64) NOT NULL,
    desired_revision       BIGINT NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    attempt_count          INT NOT NULL DEFAULT 0,
    available_at           DATETIME(3) NOT NULL,
    lease_owner            VARCHAR(128) NULL,
    lease_expires_at       DATETIME(3) NULL,
    last_error_code        VARCHAR(64) NULL,
    version                BIGINT NOT NULL DEFAULT 1,
    projected_vector_ids   JSON NOT NULL,
    completed_at           DATETIME(3) NULL,
    created_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                             ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (memory_id),
    KEY idx_memory_vector_projection_claim (
        status, available_at, lease_expires_at, memory_id
    ),
    CONSTRAINT chk_memory_vector_projection_revision
        CHECK (desired_revision >= 1),
    CONSTRAINT chk_memory_vector_projection_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_memory_vector_projection_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_memory_vector_projection_version
        CHECK (version >= 1),
    CONSTRAINT chk_memory_vector_projection_manifest
        CHECK (JSON_VALID(projected_vector_ids))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing Memory is projected only after the feature is explicitly enabled.
INSERT IGNORE INTO memory_vector_projection_work (
    memory_id, desired_revision, status, attempt_count, available_at,
    version, projected_vector_ids, created_at, updated_at
)
SELECT memory_id, 1, 'PENDING', 0, UTC_TIMESTAMP(3),
       1, JSON_ARRAY(), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM memory_item;
