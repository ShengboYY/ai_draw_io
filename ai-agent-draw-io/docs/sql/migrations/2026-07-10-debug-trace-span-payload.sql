-- Attach retained debug payloads to the span that produced them.

USE ai_draw_io;

ALTER TABLE agent_debug_trace_capture
    ADD COLUMN span_id VARCHAR(64) NULL COMMENT '关联 LLM/tool/run span;旧 run 级 capture 为空' AFTER run_id,
    ADD COLUMN payload_kind VARCHAR(64) NULL COMMENT 'INPUT | OUTPUT | TOOL_ARGS | TOOL_RESULT | ERROR' AFTER event_type,
    ADD COLUMN content_type VARCHAR(64) NOT NULL DEFAULT 'text/plain' COMMENT 'payload media type' AFTER payload_kind,
    ADD COLUMN original_length BIGINT NOT NULL DEFAULT 0 COMMENT '截断前字符数' AFTER content_sha256,
    ADD COLUMN truncated TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=content 已按上限截断' AFTER original_length,
    ADD KEY idx_debug_trace_capture_span (run_id, span_id, created_at);

-- Existing run-level captures remain readable in the richer inspector.
UPDATE agent_debug_trace_capture
SET payload_kind = event_type,
    original_length = CHAR_LENGTH(COALESCE(content, ''))
WHERE payload_kind IS NULL;

ALTER TABLE agent_debug_trace_capture
    MODIFY COLUMN payload_kind VARCHAR(64) NOT NULL COMMENT 'payload semantic kind';
