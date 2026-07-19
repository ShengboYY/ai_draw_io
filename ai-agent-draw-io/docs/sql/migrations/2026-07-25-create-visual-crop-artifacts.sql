-- WP3C-B: immutable local crops prepared before optional external visual analysis.
CREATE TABLE IF NOT EXISTS material_visual_artifact (
    revision_id VARCHAR(64) NOT NULL,
    candidate_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NOT NULL,
    page_no INT NOT NULL,
    caption_block_id VARCHAR(128) NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_version_id VARCHAR(255) NOT NULL,
    object_identity_hash BINARY(32) GENERATED ALWAYS AS (
        UNHEX(SHA2(CONCAT(CHAR_LENGTH(object_key), ':', object_key, object_version_id), 256))
    ) STORED,
    content_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, candidate_id),
    UNIQUE KEY uk_material_visual_artifact_object (object_identity_hash),
    KEY idx_material_visual_artifact_page (revision_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
