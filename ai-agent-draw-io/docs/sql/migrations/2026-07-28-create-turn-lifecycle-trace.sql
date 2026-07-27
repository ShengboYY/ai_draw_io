CREATE TABLE IF NOT EXISTS turn_lifecycle_trace (
    trace_id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_key               VARCHAR(64) NOT NULL,
    conversation_id         VARCHAR(64) NOT NULL,
    turn_id                 VARCHAR(128) NOT NULL,
    event_type              VARCHAR(32) NOT NULL,
    attempt_id              VARCHAR(128) NULL,
    attempt_epoch           BIGINT NOT NULL DEFAULT 0,
    policy_hash             CHAR(64) NULL,
    input_binding_digest    CHAR(64) NULL,
    decision_digest         CHAR(64) NULL,
    outcome_code            VARCHAR(64) NULL,
    outcome_status          VARCHAR(32) NULL,
    occurred_at             DATETIME(3) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (trace_id),
    KEY idx_turn_lifecycle_trace_turn (
        owner_key, conversation_id, turn_id, occurred_at, trace_id
    ),
    KEY idx_turn_lifecycle_trace_type (event_type, occurred_at),
    CONSTRAINT chk_turn_lifecycle_trace_attempt_epoch CHECK (attempt_epoch >= 0),
    CONSTRAINT chk_turn_lifecycle_trace_status CHECK (
        outcome_status IS NULL OR outcome_status IN (
            'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED',
            'EXPIRED_GONE', 'ORPHANED_RETRYABLE'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
