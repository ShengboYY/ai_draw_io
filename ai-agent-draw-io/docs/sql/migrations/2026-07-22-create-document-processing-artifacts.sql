-- WP3B: exact S3 version pins for immutable native/OCR/canonical page artifacts.
CREATE TABLE IF NOT EXISTS material_page_artifact (
    id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    page_no INT NOT NULL,
    artifact_kind VARCHAR(32) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    object_version_id VARCHAR(255) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_page_artifact_kind (revision_id, page_no, artifact_kind),
    UNIQUE KEY uk_material_page_artifact_object (object_key, object_version_id),
    KEY idx_material_page_artifact_revision (revision_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
