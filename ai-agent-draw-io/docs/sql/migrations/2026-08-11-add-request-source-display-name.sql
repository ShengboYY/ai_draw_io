-- Preserve the source label used by direct-source planning and user-facing diagnostics.
-- The predecessor snapshot migration already included this column in some installations, so
-- make the release safe for both the split and combined predecessor shapes.
-- Canonical upgrade: ALTER TABLE request_source_snapshot_item ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '' AFTER kind
USE ai_draw_io;

SET @request_source_display_name_columns = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'request_source_snapshot_item'
      AND COLUMN_NAME = 'display_name'
);
SET @request_source_display_name_sql = IF(
    @request_source_display_name_columns = 0,
    'ALTER TABLE request_source_snapshot_item ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '''' AFTER kind',
    'SET @request_source_display_name_noop = 1'
);
PREPARE request_source_display_name_stmt FROM @request_source_display_name_sql;
EXECUTE request_source_display_name_stmt;
DEALLOCATE PREPARE request_source_display_name_stmt;
