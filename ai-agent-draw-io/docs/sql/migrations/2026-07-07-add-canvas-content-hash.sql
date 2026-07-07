-- Adds nullable canonical content hash storage for canvas-state idempotency.
-- Existing rows are left NULL so the application can compute the canonical hash
-- with the same parser rules it uses for new saves.

USE ai_draw_io;

-- Keep this migration rerunnable because deploy smoke tests apply all SQL files on
-- every startup, including databases that were already created from diagram.sql.
SET @content_hash_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_canvas_state'
      AND COLUMN_NAME = 'content_hash'
);

SET @add_content_hash_column_sql = IF(
    @content_hash_column_count = 0,
    'ALTER TABLE diagram_canvas_state ADD COLUMN content_hash VARCHAR(80) DEFAULT NULL COMMENT ''Canonical SHA-256 hash of latest canvas XML'' AFTER current_xml',
    'SET @add_content_hash_column_noop = 1'
);

PREPARE add_content_hash_column_stmt FROM @add_content_hash_column_sql;
EXECUTE add_content_hash_column_stmt;
DEALLOCATE PREPARE add_content_hash_column_stmt;
