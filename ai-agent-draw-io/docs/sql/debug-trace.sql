-- ============================================================================
-- Debug trace controls, captures, and retained metadata.
--
-- Debug trace content may contain prompts, responses, or canvas fragments. Application
-- capture is enabled by default and can be disabled with ZIPP_TELEMETRY_DEBUG_PAYLOAD_CAPTURE_ENABLED.
-- Cleanup nulls content after retention expiry while keeping metadata for audits.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS agent_debug_trace_control (
    id                 VARCHAR(64) NOT NULL COMMENT '主键; dtc_<uuid>',
    created_by_user_id VARCHAR(64) NOT NULL COMMENT '启用 trace 的管理员用户 ID',
    scope_user_id      VARCHAR(64) NULL COMMENT '可选:仅捕获该用户',
    scope_run_id       VARCHAR(64) NULL COMMENT '可选:仅捕获该 run',
    scope_starts_at    DATETIME NULL COMMENT '可选:捕获窗口开始',
    scope_ends_at      DATETIME NULL COMMENT '可选:捕获窗口结束',
    enabled            TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=启用;0=禁用',
    created_at         DATETIME NOT NULL COMMENT '创建时间',
    disabled_at        DATETIME NULL COMMENT '禁用时间',
    PRIMARY KEY (id),
    KEY idx_debug_trace_control_enabled (enabled, disabled_at, created_at),
    KEY idx_debug_trace_control_user (scope_user_id, enabled),
    KEY idx_debug_trace_control_run (scope_run_id, enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理员 debug trace 控制';

CREATE TABLE IF NOT EXISTS agent_debug_trace_capture (
    id                 VARCHAR(64) NOT NULL COMMENT '主键; adt_<uuid>',
    control_id         VARCHAR(64) NOT NULL COMMENT '触发捕获的控制 ID;默认全量采集使用 dtc_default_all',
    user_id            VARCHAR(64) NOT NULL COMMENT '请求 owner/user;账号删除时会匿名化',
    run_id             VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    span_id            VARCHAR(64) NULL COMMENT '关联 LLM/tool/run span;旧 run 级 capture 为空',
    event_type         VARCHAR(64) NOT NULL COMMENT 'CHAT_REQUEST | ROUTED_MESSAGE | CHAT_RESPONSE 等',
    payload_kind       VARCHAR(64) NOT NULL COMMENT 'INPUT | OUTPUT | TOOL_ARGS | TOOL_RESULT | ERROR',
    content_type       VARCHAR(64) NOT NULL DEFAULT 'text/plain' COMMENT 'text/plain | application/json | application/xml',
    content            LONGTEXT NULL COMMENT '敏感 trace 内容;过期/删号时清空',
    content_sha256     VARCHAR(64) NOT NULL COMMENT '内容 SHA-256,用于过期后核对元数据',
    original_length    BIGINT NOT NULL DEFAULT 0 COMMENT '截断前字符数',
    truncated          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=content 已按上限截断',
    content_expires_at DATETIME NOT NULL COMMENT '内容默认捕获后 7 天过期',
    content_deleted_at DATETIME NULL COMMENT '内容被清理时间',
    created_at         DATETIME NOT NULL COMMENT '捕获时间',
    PRIMARY KEY (id),
    KEY idx_debug_trace_capture_run (run_id, created_at),
    KEY idx_debug_trace_capture_span (run_id, span_id, created_at),
    KEY idx_debug_trace_capture_user (user_id, created_at),
    KEY idx_debug_trace_capture_expiry (content_expires_at, content_deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'debug trace 捕获内容与保留元数据';
