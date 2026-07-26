-- Memory v1: explicit, user-confirmed Chartbook decisions only.
-- Candidate payloads are short-lived and scrubbed on every terminal transition.
USE ai_draw_io;

CREATE TABLE IF NOT EXISTS chartbook_memory_candidate (
    candidate_id             VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    chartbook_id             VARCHAR(64) NOT NULL,
    source_conversation_id   VARCHAR(128) NOT NULL,
    source_turn_id           VARCHAR(128) NOT NULL,
    source_diagram_id        VARCHAR(64) NOT NULL,
    decision_key             VARCHAR(128) NOT NULL,
    applicability_stage      VARCHAR(64) NOT NULL,
    scope                    VARCHAR(32) NOT NULL,
    canonical_text           VARCHAR(1000) NULL,
    policy_version           VARCHAR(64) NOT NULL,
    declaration_digest       VARCHAR(128) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    version                  BIGINT NOT NULL DEFAULT 1,
    expires_at               DATETIME(3) NOT NULL,
    retain_until             DATETIME(3) NOT NULL,
    payload_deleted_at       DATETIME(3) NULL,
    materialized_memory_id   VARCHAR(64) NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (candidate_id),
    UNIQUE KEY uk_memory_candidate_request (
        owner_key, chartbook_id, source_conversation_id, source_turn_id,
        declaration_digest, decision_key
    ),
    KEY idx_memory_candidate_expiry (status, expires_at),
    KEY idx_memory_candidate_owner (owner_key, chartbook_id, status),
    CONSTRAINT fk_memory_candidate_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id) ON DELETE CASCADE,
    CONSTRAINT chk_memory_candidate_status
        CHECK (status IN ('PENDING', 'MATERIALIZED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT chk_memory_candidate_scope
        CHECK (scope = 'CHARTBOOK'),
    CONSTRAINT chk_memory_candidate_version
        CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chartbook_memory (
    memory_id                VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    chartbook_id             VARCHAR(64) NOT NULL,
    source_conversation_id   VARCHAR(128) NOT NULL,
    source_turn_id           VARCHAR(128) NOT NULL,
    source_diagram_id        VARCHAR(64) NOT NULL,
    decision_key             VARCHAR(128) NOT NULL,
    applicability_stage      VARCHAR(64) NOT NULL,
    scope                    VARCHAR(32) NOT NULL,
    canonical_text           VARCHAR(1000) NOT NULL,
    policy_version           VARCHAR(64) NOT NULL,
    declaration_digest       VARCHAR(128) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    version                  BIGINT NOT NULL DEFAULT 1,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (memory_id),
    KEY idx_chartbook_memory_recall (owner_key, chartbook_id, status, decision_key),
    CONSTRAINT fk_chartbook_memory_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id) ON DELETE CASCADE,
    CONSTRAINT chk_chartbook_memory_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT chk_chartbook_memory_scope
        CHECK (scope = 'CHARTBOOK'),
    CONSTRAINT chk_chartbook_memory_version
        CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
