-- Widen agent telemetry timestamp columns from DATETIME (second granularity) to
-- DATETIME(3) (millisecond granularity).
--
-- Why: the trace timeline positions each span by started_at/occurred_at, but the
-- columns only stored whole seconds while latency_ms is millisecond-precise. Any
-- sub-second activity collapsed onto the same instant — e.g. three get_drawio_skill
-- tool calls within one second all rendered at the same x — and a bar's start
-- (quantized) no longer lined up with its width (precise), so durations looked wrong.
--
-- The write path already binds java.time.Instant (which carries millis); only the
-- column precision was truncating it. No application code change is required.
--
-- Idempotent: MODIFY COLUMN restates the full definition, so re-running is a no-op.
-- Existing rows keep their .000 fractional part; only new writes gain millisecond
-- precision.

USE ai_draw_io;

ALTER TABLE agent_run
    MODIFY started_at   DATETIME(3) NOT NULL COMMENT '开始时间',
    MODIFY completed_at DATETIME(3) NULL     COMMENT '结束时间';

ALTER TABLE agent_run_step
    MODIFY started_at   DATETIME(3) NOT NULL COMMENT '开始时间',
    MODIFY completed_at DATETIME(3) NOT NULL COMMENT '结束时间';

ALTER TABLE agent_llm_call
    MODIFY started_at   DATETIME(3) NOT NULL COMMENT '开始时间',
    MODIFY completed_at DATETIME(3) NOT NULL COMMENT '结束时间';

ALTER TABLE agent_tool_call
    MODIFY started_at   DATETIME(3) NOT NULL COMMENT '开始时间',
    MODIFY completed_at DATETIME(3) NOT NULL COMMENT '结束时间';

ALTER TABLE agent_trace_event
    MODIFY occurred_at  DATETIME(3) NOT NULL COMMENT '发生时间';
