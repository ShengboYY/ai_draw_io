-- Repair installations where the snapshot table predated the processing-source flag.
-- MySQL 5.7 lacks ADD COLUMN IF NOT EXISTS, so keep this manual migration re-runnable.
USE ai_draw_io;

SET @request_source_processing_flag_columns = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'request_source_snapshot_item'
      AND COLUMN_NAME = 'counts_as_processing_source'
);
SET @request_source_processing_flag_sql = IF(
    @request_source_processing_flag_columns = 0,
    'ALTER TABLE request_source_snapshot_item ADD COLUMN counts_as_processing_source BOOLEAN NOT NULL DEFAULT FALSE AFTER pinned',
    'SET @request_source_processing_flag_noop = 1'
);
PREPARE request_source_processing_flag_stmt FROM @request_source_processing_flag_sql;
EXECUTE request_source_processing_flag_stmt;
DEALLOCATE PREPARE request_source_processing_flag_stmt;
