-- Add span hierarchy (parent_id) to agent telemetry so a run's steps, LLM calls,
-- tool calls, and trace events form a real tree instead of a flat time-sorted list.
--
-- This migration is idempotent because some dev/staging databases were patched
-- manually while the feature was being built.

USE ai_draw_io;

SET @agent_run_trace_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND COLUMN_NAME = 'trace_id'
);

SET @add_agent_run_trace_id_sql = IF(
    @agent_run_trace_id_column_count = 0,
    'ALTER TABLE agent_run ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''预留;未来 OTel traceId 对齐用'' AFTER request_id',
    'SET @add_agent_run_trace_id_noop = 1'
);

PREPARE add_agent_run_trace_id_stmt FROM @add_agent_run_trace_id_sql;
EXECUTE add_agent_run_trace_id_stmt;
DEALLOCATE PREPARE add_agent_run_trace_id_stmt;

SET @agent_run_step_parent_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run_step'
      AND COLUMN_NAME = 'parent_id'
);

SET @add_agent_run_step_parent_id_sql = IF(
    @agent_run_step_parent_id_column_count = 0,
    'ALTER TABLE agent_run_step ADD COLUMN parent_id VARCHAR(64) NULL COMMENT ''父 span id;通常 = agent_run.id'' AFTER run_id',
    'SET @add_agent_run_step_parent_id_noop = 1'
);

PREPARE add_agent_run_step_parent_id_stmt FROM @add_agent_run_step_parent_id_sql;
EXECUTE add_agent_run_step_parent_id_stmt;
DEALLOCATE PREPARE add_agent_run_step_parent_id_stmt;

SET @agent_run_step_trace_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run_step'
      AND COLUMN_NAME = 'trace_id'
);

SET @add_agent_run_step_trace_id_sql = IF(
    @agent_run_step_trace_id_column_count = 0,
    'ALTER TABLE agent_run_step ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''预留;未来 OTel traceId 对齐用'' AFTER parent_id',
    'SET @add_agent_run_step_trace_id_noop = 1'
);

PREPARE add_agent_run_step_trace_id_stmt FROM @add_agent_run_step_trace_id_sql;
EXECUTE add_agent_run_step_trace_id_stmt;
DEALLOCATE PREPARE add_agent_run_step_trace_id_stmt;

SET @agent_run_step_parent_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run_step'
      AND INDEX_NAME = 'idx_agent_run_step_parent'
);

SET @add_agent_run_step_parent_index_sql = IF(
    @agent_run_step_parent_index_count = 0,
    'ALTER TABLE agent_run_step ADD KEY idx_agent_run_step_parent (parent_id)',
    'SET @add_agent_run_step_parent_index_noop = 1'
);

PREPARE add_agent_run_step_parent_index_stmt FROM @add_agent_run_step_parent_index_sql;
EXECUTE add_agent_run_step_parent_index_stmt;
DEALLOCATE PREPARE add_agent_run_step_parent_index_stmt;

SET @agent_llm_call_parent_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_llm_call'
      AND COLUMN_NAME = 'parent_id'
);

SET @add_agent_llm_call_parent_id_sql = IF(
    @agent_llm_call_parent_id_column_count = 0,
    'ALTER TABLE agent_llm_call ADD COLUMN parent_id VARCHAR(64) NULL COMMENT ''父 span id;所在 step.id 或 run.id'' AFTER run_id',
    'SET @add_agent_llm_call_parent_id_noop = 1'
);

PREPARE add_agent_llm_call_parent_id_stmt FROM @add_agent_llm_call_parent_id_sql;
EXECUTE add_agent_llm_call_parent_id_stmt;
DEALLOCATE PREPARE add_agent_llm_call_parent_id_stmt;

SET @agent_llm_call_trace_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_llm_call'
      AND COLUMN_NAME = 'trace_id'
);

SET @add_agent_llm_call_trace_id_sql = IF(
    @agent_llm_call_trace_id_column_count = 0,
    'ALTER TABLE agent_llm_call ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''预留;未来 OTel traceId 对齐用'' AFTER parent_id',
    'SET @add_agent_llm_call_trace_id_noop = 1'
);

PREPARE add_agent_llm_call_trace_id_stmt FROM @add_agent_llm_call_trace_id_sql;
EXECUTE add_agent_llm_call_trace_id_stmt;
DEALLOCATE PREPARE add_agent_llm_call_trace_id_stmt;

SET @agent_llm_call_parent_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_llm_call'
      AND INDEX_NAME = 'idx_agent_llm_call_parent'
);

SET @add_agent_llm_call_parent_index_sql = IF(
    @agent_llm_call_parent_index_count = 0,
    'ALTER TABLE agent_llm_call ADD KEY idx_agent_llm_call_parent (parent_id)',
    'SET @add_agent_llm_call_parent_index_noop = 1'
);

PREPARE add_agent_llm_call_parent_index_stmt FROM @add_agent_llm_call_parent_index_sql;
EXECUTE add_agent_llm_call_parent_index_stmt;
DEALLOCATE PREPARE add_agent_llm_call_parent_index_stmt;

SET @agent_tool_call_parent_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_tool_call'
      AND COLUMN_NAME = 'parent_id'
);

SET @add_agent_tool_call_parent_id_sql = IF(
    @agent_tool_call_parent_id_column_count = 0,
    'ALTER TABLE agent_tool_call ADD COLUMN parent_id VARCHAR(64) NULL COMMENT ''父 span id;所在 step.id 或 run.id'' AFTER run_id',
    'SET @add_agent_tool_call_parent_id_noop = 1'
);

PREPARE add_agent_tool_call_parent_id_stmt FROM @add_agent_tool_call_parent_id_sql;
EXECUTE add_agent_tool_call_parent_id_stmt;
DEALLOCATE PREPARE add_agent_tool_call_parent_id_stmt;

SET @agent_tool_call_trace_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_tool_call'
      AND COLUMN_NAME = 'trace_id'
);

SET @add_agent_tool_call_trace_id_sql = IF(
    @agent_tool_call_trace_id_column_count = 0,
    'ALTER TABLE agent_tool_call ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''预留;未来 OTel traceId 对齐用'' AFTER parent_id',
    'SET @add_agent_tool_call_trace_id_noop = 1'
);

PREPARE add_agent_tool_call_trace_id_stmt FROM @add_agent_tool_call_trace_id_sql;
EXECUTE add_agent_tool_call_trace_id_stmt;
DEALLOCATE PREPARE add_agent_tool_call_trace_id_stmt;

SET @agent_tool_call_parent_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_tool_call'
      AND INDEX_NAME = 'idx_agent_tool_call_parent'
);

SET @add_agent_tool_call_parent_index_sql = IF(
    @agent_tool_call_parent_index_count = 0,
    'ALTER TABLE agent_tool_call ADD KEY idx_agent_tool_call_parent (parent_id)',
    'SET @add_agent_tool_call_parent_index_noop = 1'
);

PREPARE add_agent_tool_call_parent_index_stmt FROM @add_agent_tool_call_parent_index_sql;
EXECUTE add_agent_tool_call_parent_index_stmt;
DEALLOCATE PREPARE add_agent_tool_call_parent_index_stmt;

SET @agent_trace_event_table_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_trace_event'
);

SET @agent_trace_event_parent_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_trace_event'
      AND COLUMN_NAME = 'parent_id'
);

SET @add_agent_trace_event_parent_id_sql = IF(
    @agent_trace_event_table_count > 0 AND @agent_trace_event_parent_id_column_count = 0,
    'ALTER TABLE agent_trace_event ADD COLUMN parent_id VARCHAR(64) NULL COMMENT ''父 span id;通常 = agent_run.id'' AFTER run_id',
    'SET @add_agent_trace_event_parent_id_noop = 1'
);

PREPARE add_agent_trace_event_parent_id_stmt FROM @add_agent_trace_event_parent_id_sql;
EXECUTE add_agent_trace_event_parent_id_stmt;
DEALLOCATE PREPARE add_agent_trace_event_parent_id_stmt;

SET @agent_trace_event_trace_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_trace_event'
      AND COLUMN_NAME = 'trace_id'
);

SET @add_agent_trace_event_trace_id_sql = IF(
    @agent_trace_event_table_count > 0 AND @agent_trace_event_trace_id_column_count = 0,
    'ALTER TABLE agent_trace_event ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''预留;未来 OTel traceId 对齐用'' AFTER parent_id',
    'SET @add_agent_trace_event_trace_id_noop = 1'
);

PREPARE add_agent_trace_event_trace_id_stmt FROM @add_agent_trace_event_trace_id_sql;
EXECUTE add_agent_trace_event_trace_id_stmt;
DEALLOCATE PREPARE add_agent_trace_event_trace_id_stmt;

SET @agent_trace_event_parent_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_trace_event'
      AND INDEX_NAME = 'idx_agent_trace_event_parent'
);

SET @add_agent_trace_event_parent_index_sql = IF(
    @agent_trace_event_table_count > 0 AND @agent_trace_event_parent_index_count = 0,
    'ALTER TABLE agent_trace_event ADD KEY idx_agent_trace_event_parent (parent_id)',
    'SET @add_agent_trace_event_parent_index_noop = 1'
);

PREPARE add_agent_trace_event_parent_index_stmt FROM @add_agent_trace_event_parent_index_sql;
EXECUTE add_agent_trace_event_parent_index_stmt;
DEALLOCATE PREPARE add_agent_trace_event_parent_index_stmt;
