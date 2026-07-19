-- WP3C-B3d: PARTIAL_READY is allowed only with an exact-version, checksummed gap manifest.
ALTER TABLE material_processing_revision
    ADD COLUMN gap_manifest_object_version_id VARCHAR(255) NULL AFTER gap_manifest_key,
    ADD COLUMN gap_manifest_sha256 CHAR(64) NULL AFTER gap_manifest_object_version_id,
    ADD COLUMN gap_manifest_size BIGINT NULL AFTER gap_manifest_sha256,
    ADD COLUMN gap_manifest_content_type VARCHAR(128) NULL AFTER gap_manifest_size,
    ADD CONSTRAINT chk_revision_gap_manifest_pin CHECK (
        (gap_manifest_key IS NULL AND gap_manifest_object_version_id IS NULL
            AND gap_manifest_sha256 IS NULL AND gap_manifest_size IS NULL
            AND gap_manifest_content_type IS NULL)
        OR
        (gap_manifest_key IS NOT NULL AND gap_manifest_object_version_id IS NOT NULL
            AND gap_manifest_sha256 IS NOT NULL AND gap_manifest_size IS NOT NULL
            AND gap_manifest_content_type IS NOT NULL)
    );
