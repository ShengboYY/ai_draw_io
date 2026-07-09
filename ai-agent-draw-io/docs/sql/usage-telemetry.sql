-- ============================================================================
-- Metadata-only agent usage telemetry for account usage pages.
--
-- These tables deliberately do NOT include prompt text, response text, system
-- prompts, raw API keys, encrypted API key material, or full Draw.io XML.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS agent_run (
    id                  VARCHAR(64) NOT NULL COMMENT '主键; aru_<uuid>',
    request_id          VARCHAR(128) NULL COMMENT '入口请求相关 ID;前端/响应头/SSE 共用',
    trace_id            VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用',
    user_id             VARCHAR(64) NOT NULL COMMENT '请求所属 owner/user',
    agent_id            VARCHAR(64) NOT NULL COMMENT '用户可见 agent id',
    session_id          VARCHAR(128) NULL COMMENT 'ADK session id;非敏感能力标识',
    request_type        VARCHAR(32) NOT NULL COMMENT 'chat | chat_stream',
    credential_source   VARCHAR(24) NOT NULL COMMENT 'PLATFORM | USER_KEY',
    model_credential_id VARCHAR(64) NULL COMMENT '用户保存的凭据 ID;不含密钥',
    status              VARCHAR(24) NOT NULL COMMENT 'RUNNING | SUCCESS | FAILED',
    error_class         VARCHAR(128) NULL COMMENT '异常类名;不存异常消息',
    started_at          DATETIME NOT NULL COMMENT '开始时间',
    completed_at        DATETIME NULL COMMENT '结束时间',
    latency_ms          BIGINT NULL COMMENT '总耗时',
    PRIMARY KEY (id),
    KEY idx_agent_run_request (request_id),
    KEY idx_agent_run_completed (completed_at),
    KEY idx_agent_run_user_started (user_id, started_at),
    KEY idx_agent_run_user_source (user_id, credential_source, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户可见 agent 请求元数据';

CREATE TABLE IF NOT EXISTS agent_trace_event (
    id            VARCHAR(64) NOT NULL COMMENT '主键; ate_<uuid>',
    run_id        VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    parent_id     VARCHAR(64) NULL COMMENT '父 span id;通常 = agent_run.id',
    trace_id      VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用',
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
    KEY idx_agent_trace_event_parent (parent_id),
    KEY idx_agent_trace_event_request (request_id),
    KEY idx_agent_trace_event_user_time (user_id, occurred_at),
    KEY idx_agent_trace_event_time (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'agent 横切生命周期 trace 元数据';

CREATE TABLE IF NOT EXISTS agent_run_step (
    id           VARCHAR(64) NOT NULL COMMENT '主键; ars_<uuid>',
    run_id       VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    parent_id    VARCHAR(64) NULL COMMENT '父 span id;通常 = agent_run.id',
    trace_id     VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用',
    user_id      VARCHAR(64) NOT NULL COMMENT '冗余 owner/user,便于按用户聚合',
    phase        VARCHAR(32) NOT NULL COMMENT 'routing | drawing | review | repair | direct_answer',
    status       VARCHAR(24) NOT NULL COMMENT 'SUCCESS | FAILED',
    error_class  VARCHAR(128) NULL COMMENT '异常类名;不存异常消息',
    started_at   DATETIME NOT NULL COMMENT '开始时间',
    completed_at DATETIME NOT NULL COMMENT '结束时间',
    latency_ms   BIGINT NOT NULL COMMENT '耗时',
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id),
    KEY idx_agent_run_step_parent (parent_id),
    KEY idx_agent_run_step_user_phase (user_id, phase, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'agent 逻辑阶段元数据';

CREATE TABLE IF NOT EXISTS agent_llm_call (
    id                  VARCHAR(64) NOT NULL COMMENT '主键; alc_<uuid>',
    run_id              VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    parent_id           VARCHAR(64) NULL COMMENT '父 span id;所在 step.id 或 run.id',
    trace_id            VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用',
    user_id             VARCHAR(64) NOT NULL COMMENT '冗余 owner/user,便于按用户聚合',
    phase               VARCHAR(32) NOT NULL COMMENT '触发模型调用的逻辑阶段',
    provider            VARCHAR(64) NOT NULL COMMENT '模型服务商',
    model               VARCHAR(128) NOT NULL COMMENT '模型名或 unknown',
    credential_source   VARCHAR(24) NOT NULL COMMENT 'PLATFORM | USER_KEY',
    model_credential_id VARCHAR(64) NULL COMMENT '用户保存的凭据 ID;不含密钥',
    prompt_tokens       INT NULL COMMENT 'provider 未返回时为 NULL,不是 0',
    completion_tokens   INT NULL COMMENT 'provider 未返回时为 NULL,不是 0',
    total_tokens        INT NULL COMMENT 'provider 未返回时为 NULL,不是 0',
    status              VARCHAR(24) NOT NULL COMMENT 'SUCCESS | FAILED',
    error_class         VARCHAR(128) NULL COMMENT '异常类名;不存异常消息',
    started_at          DATETIME NOT NULL COMMENT '开始时间',
    completed_at        DATETIME NOT NULL COMMENT '结束时间',
    latency_ms          BIGINT NULL COMMENT '耗时',
    PRIMARY KEY (id),
    KEY idx_agent_llm_call_run (run_id),
    KEY idx_agent_llm_call_parent (parent_id),
    KEY idx_agent_llm_call_user_source (user_id, credential_source, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'LLM 调用用量元数据';

CREATE TABLE IF NOT EXISTS agent_tool_call (
    id           VARCHAR(64) NOT NULL COMMENT '主键; atc_<uuid>',
    run_id       VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    parent_id    VARCHAR(64) NULL COMMENT '父 span id;所在 step.id 或 run.id',
    trace_id     VARCHAR(64) NULL COMMENT '预留;未来 OTel traceId 对齐用',
    user_id      VARCHAR(64) NOT NULL COMMENT '冗余 owner/user,便于按用户聚合',
    phase        VARCHAR(32) NOT NULL COMMENT '触发工具调用的逻辑阶段',
    tool_name    VARCHAR(128) NOT NULL COMMENT '工具名称',
    status       VARCHAR(24) NOT NULL COMMENT 'SUCCESS | FAILED',
    error_class  VARCHAR(128) NULL COMMENT '异常类名;不存异常消息',
    started_at   DATETIME NOT NULL COMMENT '开始时间',
    completed_at DATETIME NOT NULL COMMENT '结束时间',
    latency_ms   BIGINT NULL COMMENT '耗时',
    PRIMARY KEY (id),
    KEY idx_agent_tool_call_run (run_id),
    KEY idx_agent_tool_call_parent (parent_id),
    KEY idx_agent_tool_call_user_tool (user_id, tool_name, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '工具调用元数据';
