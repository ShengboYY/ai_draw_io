ALTER TABLE agent_llm_call
    ADD COLUMN provider_request_id VARCHAR(255) NULL
        COMMENT 'provider 实际请求 ID;ADK 未暴露时为 NULL' AFTER total_tokens,
    ADD COLUMN provider_response_id VARCHAR(255) NULL
        COMMENT 'provider 实际响应 ID;ADK 未暴露时为 NULL' AFTER provider_request_id,
    ADD COLUMN ttft_ms BIGINT NULL
        COMMENT '首个模型响应 callback 延迟;无响应时为 NULL' AFTER provider_response_id,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 1
        COMMENT '当前可观测模型调用的 attempt 数' AFTER ttft_ms,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0
        COMMENT '当前可观测模型调用的 retry 数' AFTER attempt_count;
