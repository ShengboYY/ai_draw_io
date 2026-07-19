-- WP3C-B: exact object-version pin for the immutable whole-document structure manifest.
CREATE TABLE IF NOT EXISTS material_revision_artifact (
    id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    artifact_kind VARCHAR(32) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_version_id VARCHAR(255) NOT NULL,
    -- Hash the complete immutable S3 identity so utf8mb4 key columns stay outside index byte limits.
    object_identity_hash BINARY(32) GENERATED ALWAYS AS (
        UNHEX(SHA2(CONCAT(CHAR_LENGTH(object_key), ':', object_key, object_version_id), 256))
    ) STORED,
    content_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_revision_artifact_kind (revision_id, artifact_kind),
    UNIQUE KEY uk_material_revision_artifact_object (object_identity_hash),
    KEY idx_material_revision_artifact_revision (revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
