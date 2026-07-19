-- WP3C-B3e: immutable compatibility campaigns, shadow evidence, and rollback-safe generation switches.
ALTER TABLE rag_index_generation
    ADD COLUMN shadow_started_at DATETIME(3) NULL AFTER activated_at,
    ADD COLUMN retired_at DATETIME(3) NULL AFTER shadow_started_at,
    ADD COLUMN rollback_until DATETIME(3) NULL AFTER retired_at,
    ADD COLUMN activation_report_id VARCHAR(64) NULL AFTER rollback_until,
    ADD COLUMN previous_generation_id VARCHAR(64) NULL AFTER activation_report_id;

CREATE TABLE IF NOT EXISTS rag_index_generation_compatibility_profile (
    index_generation_id VARCHAR(64) NOT NULL,
    tokenizer_fingerprint VARCHAR(255) NOT NULL,
    campaign_fingerprint CHAR(64) NOT NULL,
    target_generation BIGINT NOT NULL DEFAULT 0,
    registered_at DATETIME(3) NOT NULL,
    PRIMARY KEY (index_generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_generation_target (
    index_generation_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    tokenizer_fingerprint VARCHAR(255) NOT NULL,
    state VARCHAR(16) NOT NULL,
    required_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (index_generation_id, revision_id),
    KEY idx_generation_target_state (index_generation_id, state, required_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_shadow_report (
    report_id VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    baseline_generation_id VARCHAR(64) NOT NULL,
    target_generation BIGINT NOT NULL,
    policy_fingerprint CHAR(64) NOT NULL,
    sample_count INT NOT NULL,
    authorization_mismatch_count INT NOT NULL,
    candidate_recall_at_40 DECIMAL(8,6) NOT NULL,
    baseline_recall_at_40 DECIMAL(8,6) NOT NULL,
    candidate_ndcg_at_16 DECIMAL(8,6) NOT NULL,
    baseline_ndcg_at_16 DECIMAL(8,6) NOT NULL,
    candidate_p95_latency_ms BIGINT NOT NULL,
    baseline_p95_latency_ms BIGINT NOT NULL,
    evaluated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_generation_shadow_report (index_generation_id, report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
