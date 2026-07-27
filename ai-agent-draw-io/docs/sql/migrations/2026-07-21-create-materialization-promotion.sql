-- WP3A validated-content materialization and original-object promotion.
-- Apply after 2026-07-20-create-secure-upload-intake.sql.
USE ai_draw_io;

SET @wp3_upload_columns = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material_upload_session'
      AND COLUMN_NAME = 'content_blob_id'
);
SET @wp3_sql = IF(@wp3_upload_columns = 0,
    'ALTER TABLE material_upload_session ADD COLUMN content_blob_id VARCHAR(64) NULL AFTER version_id, ADD COLUMN processing_revision_id VARCHAR(64) NULL AFTER content_blob_id, ADD COLUMN material_lifecycle_generation BIGINT NULL AFTER processing_revision_id, ADD KEY idx_material_upload_materialization (content_blob_id, processing_revision_id)',
    'SET @wp3_noop = 1');
PREPARE wp3_stmt FROM @wp3_sql;
EXECUTE wp3_stmt;
DEALLOCATE PREPARE wp3_stmt;

SET @wp3_blob_columns = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material_content_blob'
      AND COLUMN_NAME = 'original_object_version_id'
);
SET @wp3_sql = IF(@wp3_blob_columns = 0,
    'ALTER TABLE material_content_blob ADD COLUMN original_object_version_id VARCHAR(255) NULL AFTER original_object_key, ADD COLUMN s3_etag VARCHAR(255) NULL AFTER original_object_version_id, ADD COLUMN s3_checksum_sha256 VARCHAR(128) NULL AFTER s3_etag, ADD COLUMN promoted_at DATETIME(3) NULL AFTER status, ADD KEY idx_material_blob_status (status, updated_at)',
    'SET @wp3_noop = 1');
PREPARE wp3_stmt FROM @wp3_sql;
EXECUTE wp3_stmt;
DEALLOCATE PREPARE wp3_stmt;
