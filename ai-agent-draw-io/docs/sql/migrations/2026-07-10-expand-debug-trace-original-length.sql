ALTER TABLE agent_debug_trace_capture
    MODIFY COLUMN original_length BIGINT NOT NULL DEFAULT 0 COMMENT '截断前真实字符数';
