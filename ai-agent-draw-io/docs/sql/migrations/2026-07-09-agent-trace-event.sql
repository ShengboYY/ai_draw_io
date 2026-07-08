-- P3/P5 telemetry trace events and retention-ready indexes.

USE ai_draw_io;

SET @agent_run_request_id_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND COLUMN_NAME = 'request_id'
);

SET @add_agent_run_request_id_sql = IF(
    @agent_run_request_id_column_count = 0,
    'ALTER TABLE agent_run ADD COLUMN request_id VARCHAR(128) NULL COMMENT ''入口请求相关 ID;前端/响应头/SSE 共用'' AFTER id',
    'SET @add_agent_run_request_id_noop = 1'
);

PREPARE add_agent_run_request_id_stmt FROM @add_agent_run_request_id_sql;
EXECUTE add_agent_run_request_id_stmt;
DEALLOCATE PREPARE add_agent_run_request_id_stmt;

SET @agent_run_request_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND INDEX_NAME = 'idx_agent_run_request'
);

SET @add_agent_run_request_index_sql = IF(
    @agent_run_request_index_count = 0,
    'ALTER TABLE agent_run ADD KEY idx_agent_run_request (request_id)',
    'SET @add_agent_run_request_index_noop = 1'
);

PREPARE add_agent_run_request_index_stmt FROM @add_agent_run_request_index_sql;
EXECUTE add_agent_run_request_index_stmt;
DEALLOCATE PREPARE add_agent_run_request_index_stmt;

SET @agent_run_completed_index_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_run'
      AND INDEX_NAME = 'idx_agent_run_completed'
);

SET @add_agent_run_completed_index_sql = IF(
    @agent_run_completed_index_count = 0,
    'ALTER TABLE agent_run ADD KEY idx_agent_run_completed (completed_at)',
    'SET @add_agent_run_completed_index_noop = 1'
);

PREPARE add_agent_run_completed_index_stmt FROM @add_agent_run_completed_index_sql;
EXECUTE add_agent_run_completed_index_stmt;
DEALLOCATE PREPARE add_agent_run_completed_index_stmt;

CREATE TABLE IF NOT EXISTS agent_trace_event (
    id            VARCHAR(64) NOT NULL COMMENT '主键; ate_<uuid>',
    run_id        VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    request_id    VARCHAR(128) NULL COMMENT '入口请求相关 ID',
    user_id       VARCHAR(64) NOT NULL COMMENT '冗余 owner/user,便于匿名化和排查',
    sequence_no   BIGINT NOT NULL COMMENT 'run 内单调序号;由 RunContext 生成',
    event_type    VARCHAR(64) NOT NULL COMMENT 'HTTP_REQUEST_RECEIVED | STREAM_META_SENT | ROUTING_DECIDED | STREAM_DONE 等',
    phase         VARCHAR(32) NOT NULL COMMENT 'request | stream | routing | review | drawing',
    status        VARCHAR(24) NOT NULL COMMENT 'SUCCESS | FAILED',
    metadata_json VARCHAR(2000) NULL COMMENT '脱敏后的结构化元数据;不存 prompt/canvas/raw key',
    occurred_at   DATETIME NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_agent_trace_event_run (run_id, sequence_no),
    KEY idx_agent_trace_event_request (request_id),
    KEY idx_agent_trace_event_user_time (user_id, occurred_at),
    KEY idx_agent_trace_event_time (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'agent 横切生命周期 trace 元数据';
