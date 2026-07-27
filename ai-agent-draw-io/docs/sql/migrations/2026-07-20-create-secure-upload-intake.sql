-- WP2 secure upload intake. Apply after 2026-07-19-create-material-rag-foundation.sql.
USE ai_draw_io;

CREATE TABLE IF NOT EXISTS material_upload_rate_bucket (
    subject_type VARCHAR(16) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    hour_bucket DATETIME(3) NOT NULL,
    upload_count INT NOT NULL DEFAULT 0,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (subject_type, subject_key, hour_bucket),
    KEY idx_material_upload_rate_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Keep the migration re-runnable for local environments that apply SQL files manually.
SET @wp2_columns = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material_upload_session' AND COLUMN_NAME = 'error_code'
);
SET @wp2_sql = IF(@wp2_columns = 0,
    'ALTER TABLE material_upload_session ADD COLUMN error_code VARCHAR(64) NULL AFTER generation',
    'SET @wp2_noop = 1');
PREPARE wp2_stmt FROM @wp2_sql;
EXECUTE wp2_stmt;
DEALLOCATE PREPARE wp2_stmt;

SET @wp2_columns = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material_upload_session' AND COLUMN_NAME = 'actual_size'
);
SET @wp2_sql = IF(@wp2_columns = 0,
    'ALTER TABLE material_upload_session ADD COLUMN actual_size BIGINT NULL AFTER expected_sha256, ADD COLUMN actual_sha256 CHAR(64) NULL AFTER actual_size, ADD COLUMN detected_mime VARCHAR(128) NULL AFTER declared_mime, ADD COLUMN page_count INT NULL AFTER detected_mime, ADD COLUMN pixel_count BIGINT NULL AFTER page_count, ADD COLUMN security_status VARCHAR(24) NULL AFTER pixel_count, ADD COLUMN security_validated_at DATETIME(3) NULL AFTER security_status',
    'SET @wp2_noop = 1');
PREPARE wp2_stmt FROM @wp2_sql;
EXECUTE wp2_stmt;
DEALLOCATE PREPARE wp2_stmt;
