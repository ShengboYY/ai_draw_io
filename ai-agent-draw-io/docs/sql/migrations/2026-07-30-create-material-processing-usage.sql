-- WP3C-B3d: record billable page processing once per owner-scoped content blob.
CREATE TABLE IF NOT EXISTS material_processing_usage (
    content_blob_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    first_revision_id VARCHAR(64) NOT NULL,
    page_count INT NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (content_blob_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
