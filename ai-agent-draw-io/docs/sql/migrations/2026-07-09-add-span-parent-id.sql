-- Add span hierarchy (parent_id) to agent telemetry so a run's steps, LLM calls,
-- tool calls, and trace events form a real tree instead of a flat time-sorted list.
--
-- parent_id points at the parent span's id:
--   * agent_run_step.parent_id    -> agent_run.id            (steps hang under the run)
--   * agent_llm_call.parent_id    -> agent_run_step.id       (calls hang under the active step)
--   * agent_tool_call.parent_id   -> agent_run_step.id         when a step context is in scope,
--                                    else agent_run.id
--   * agent_trace_event.parent_id -> agent_run.id            (run-level lifecycle events)
--
-- trace_id is reserved for a future OpenTelemetry infrastructure-layer stitch (方案 B):
-- it stays NULL today and is written only once OTel spans need to align with a run.
-- Existing rows keep parent_id = NULL and degrade gracefully to a flat timeline.

USE ai_draw_io;

ALTER TABLE agent_run
    ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用';

ALTER TABLE agent_run_step
    ADD COLUMN parent_id VARCHAR(64) NULL COMMENT '父 span id;通常 = agent_run.id' AFTER run_id,
    ADD COLUMN trace_id  VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用' AFTER parent_id,
    ADD KEY idx_agent_run_step_parent (parent_id);

ALTER TABLE agent_llm_call
    ADD COLUMN parent_id VARCHAR(64) NULL COMMENT '父 span id;所在 step.id 或 run.id' AFTER run_id,
    ADD COLUMN trace_id  VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用' AFTER parent_id,
    ADD KEY idx_agent_llm_call_parent (parent_id);

ALTER TABLE agent_tool_call
    ADD COLUMN parent_id VARCHAR(64) NULL COMMENT '父 span id;所在 step.id 或 run.id' AFTER run_id,
    ADD COLUMN trace_id  VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用' AFTER parent_id,
    ADD KEY idx_agent_tool_call_parent (parent_id);

ALTER TABLE agent_trace_event
    ADD COLUMN parent_id VARCHAR(64) NULL COMMENT '父 span id;通常 = agent_run.id' AFTER run_id,
    ADD COLUMN trace_id  VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用' AFTER parent_id,
    ADD KEY idx_agent_trace_event_parent (parent_id);
