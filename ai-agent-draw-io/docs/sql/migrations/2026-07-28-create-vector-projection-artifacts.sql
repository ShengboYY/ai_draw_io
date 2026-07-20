-- WP3C-B3c: durable generation-scoped embedding/upsert batches and exact projection manifests.
ALTER TABLE rag_index_generation
    ADD COLUMN namespace VARCHAR(128) NOT NULL AFTER index_name,
    ADD COLUMN embedding_model_fingerprint CHAR(64) NOT NULL AFTER embedding_model;

ALTER TABLE retrieval_chunk_vector_projection
    ADD COLUMN batch_no INT NOT NULL DEFAULT 0 AFTER index_generation_id,
    ADD KEY idx_vector_projection_batch (index_generation_id, batch_no, state);

CREATE TABLE IF NOT EXISTS retrieval_revision_vector_projection (
    revision_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    tokenizer_fingerprint VARCHAR(255) NOT NULL,
    plan_fingerprint CHAR(64) NOT NULL,
    projection_role VARCHAR(24) NOT NULL,
    expected_projection_count INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, index_generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_vector_batch (
    revision_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    batch_no INT NOT NULL,
    work_key VARCHAR(160) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    vector_object_key VARCHAR(1024) NULL,
    vector_object_version_id VARCHAR(255) NULL,
    vector_content_sha256 CHAR(64) NULL,
    vector_byte_size BIGINT NULL,
    vector_content_type VARCHAR(128) NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, index_generation_id, batch_no),
    UNIQUE KEY uk_vector_batch_work (revision_id, work_key),
    CONSTRAINT chk_vector_batch_artifact_pin CHECK (
        (vector_object_key IS NULL AND vector_object_version_id IS NULL
            AND vector_content_sha256 IS NULL AND vector_byte_size IS NULL AND vector_content_type IS NULL)
        OR
        (vector_object_key IS NOT NULL AND vector_object_version_id IS NOT NULL
            AND vector_content_sha256 IS NOT NULL AND vector_byte_size IS NOT NULL AND vector_content_type IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_projection_manifest (
    revision_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_version_id VARCHAR(255) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    projection_count INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    object_identity_hash CHAR(64) GENERATED ALWAYS AS (
        SHA2(CONCAT(object_key, CHAR(0), object_version_id), 256)
    ) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, index_generation_id),
    UNIQUE KEY uk_projection_manifest_object (object_identity_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
