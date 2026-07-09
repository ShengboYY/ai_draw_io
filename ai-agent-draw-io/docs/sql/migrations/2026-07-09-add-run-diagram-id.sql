-- Link agent runs back to the diagram that triggered them.
-- This is nullable so old runs and answer-only requests remain valid.

USE ai_draw_io;

SET @agent_run_diagram_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND COLUMN_NAME = 'diagram_id'
);

SET @add_agent_run_diagram_id_sql = IF(
    @agent_run_diagram_id_column_count = 0,
    'ALTER TABLE agent_run ADD COLUMN diagram_id VARCHAR(64) NULL COMMENT ''关联 diagram.id;为空表示旧 run 或无持久图'' AFTER request_id',
    'SET @add_agent_run_diagram_id_noop = 1'
);

PREPARE add_agent_run_diagram_id_stmt FROM @add_agent_run_diagram_id_sql;
EXECUTE add_agent_run_diagram_id_stmt;
DEALLOCATE PREPARE add_agent_run_diagram_id_stmt;

SET @agent_run_diagram_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND INDEX_NAME = 'idx_agent_run_diagram'
);

SET @add_agent_run_diagram_index_sql = IF(
    @agent_run_diagram_index_count = 0,
    'ALTER TABLE agent_run ADD KEY idx_agent_run_diagram (diagram_id, started_at)',
    'SET @add_agent_run_diagram_index_noop = 1'
);

PREPARE add_agent_run_diagram_index_stmt FROM @add_agent_run_diagram_index_sql;
EXECUTE add_agent_run_diagram_index_stmt;
DEALLOCATE PREPARE add_agent_run_diagram_index_stmt;
