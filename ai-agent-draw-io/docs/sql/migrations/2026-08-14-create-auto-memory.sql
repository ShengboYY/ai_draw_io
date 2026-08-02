-- Auto Memory v1: one tenant-fenced store for USER and CHARTBOOK long-term Memory.
-- Session Memory remains conversation data; evidence stores bounded observations, not raw turns.
USE ai_draw_io;

CREATE TABLE IF NOT EXISTS memory_item (
    memory_id                VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    scope_type               VARCHAR(16) NOT NULL,
    scope_key                VARCHAR(64) NOT NULL,
    chartbook_id             VARCHAR(64) NULL,
    memory_type              VARCHAR(16) NOT NULL,
    semantic_key             VARCHAR(128) NOT NULL,
    title                    VARCHAR(160) NOT NULL,
    canonical_text           VARCHAR(1000) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    confidence               DECIMAL(5, 4) NOT NULL,
    evidence_count           INT NOT NULL,
    is_explicit              TINYINT(1) NOT NULL,
    policy_version           VARCHAR(64) NOT NULL,
    source_conversation_id   VARCHAR(128) NOT NULL,
    source_turn_id           VARCHAR(128) NOT NULL,
    source_diagram_id        VARCHAR(64) NULL,
    version                  BIGINT NOT NULL DEFAULT 1,
    activated_at             DATETIME(3) NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (memory_id),
    UNIQUE KEY uk_memory_item_semantic (
        owner_key, scope_type, scope_key, semantic_key
    ),
    KEY idx_memory_item_recall (
        owner_key, scope_type, scope_key, status, updated_at
    ),
    KEY idx_memory_item_chartbook (chartbook_id, status),
    CONSTRAINT fk_memory_item_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id) ON DELETE CASCADE,
    CONSTRAINT chk_memory_item_scope_type
        CHECK (scope_type IN ('USER', 'CHARTBOOK')),
    CONSTRAINT chk_memory_item_scope_target
        CHECK (
            (scope_type = 'USER' AND scope_key = owner_key AND chartbook_id IS NULL)
            OR
            (scope_type = 'CHARTBOOK' AND chartbook_id = scope_key)
        ),
    CONSTRAINT chk_memory_item_type
        CHECK (memory_type IN ('PREFERENCE', 'FEEDBACK', 'PROJECT', 'REFERENCE')),
    CONSTRAINT chk_memory_item_status
        CHECK (status IN ('OBSERVED', 'ACTIVE', 'DISABLED', 'DELETED')),
    CONSTRAINT chk_memory_item_confidence
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_memory_item_evidence_count
        CHECK (evidence_count >= 1),
    CONSTRAINT chk_memory_item_version
        CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS memory_evidence (
    evidence_id              VARCHAR(64) NOT NULL,
    memory_id                VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    source_conversation_id   VARCHAR(128) NOT NULL,
    source_turn_id           VARCHAR(128) NOT NULL,
    source_diagram_id        VARCHAR(64) NULL,
    observation_kind         VARCHAR(16) NOT NULL,
    observation_digest       CHAR(64) NOT NULL,
    observed_text            VARCHAR(1000) NOT NULL,
    confidence               DECIMAL(5, 4) NOT NULL,
    disposition              VARCHAR(16) NOT NULL,
    policy_version           VARCHAR(64) NOT NULL,
    observed_at              DATETIME(3) NOT NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (evidence_id),
    UNIQUE KEY uk_memory_evidence_turn (
        memory_id, source_conversation_id, source_turn_id, observation_digest
    ),
    KEY idx_memory_evidence_item (memory_id, disposition, observed_at),
    CONSTRAINT fk_memory_evidence_item
        FOREIGN KEY (memory_id) REFERENCES memory_item (memory_id) ON DELETE CASCADE,
    CONSTRAINT chk_memory_evidence_kind
        CHECK (observation_kind IN ('EXPLICIT', 'INFERRED', 'MIGRATED', 'USER_EDIT')),
    CONSTRAINT chk_memory_evidence_disposition
        CHECK (disposition IN ('SUPPORTING', 'CONFLICTING', 'SUPERSEDED')),
    CONSTRAINT chk_memory_evidence_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The successful Turn transaction writes only this identity row. A leased worker loads the
-- canonical user message and pinned declaration after commit, then calls the tool-free extractor.
CREATE TABLE IF NOT EXISTS memory_extraction_work (
    work_id                  VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    source_conversation_id   VARCHAR(128) NOT NULL,
    source_turn_id           VARCHAR(128) NOT NULL,
    source_diagram_id        VARCHAR(64) NOT NULL,
    chartbook_id             VARCHAR(64) NULL,
    status                   VARCHAR(16) NOT NULL,
    attempt_count            INT NOT NULL DEFAULT 0,
    available_at             DATETIME(3) NOT NULL,
    lease_owner              VARCHAR(128) NULL,
    lease_expires_at         DATETIME(3) NULL,
    last_error_code          VARCHAR(64) NULL,
    version                  BIGINT NOT NULL DEFAULT 1,
    completed_at             DATETIME(3) NULL,
    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (work_id),
    UNIQUE KEY uk_memory_extraction_turn (
        owner_key, source_conversation_id, source_turn_id
    ),
    KEY idx_memory_extraction_claim (status, available_at, lease_expires_at),
    KEY idx_memory_extraction_chartbook (owner_key, chartbook_id, status),
    CONSTRAINT fk_memory_extraction_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id) ON DELETE SET NULL,
    CONSTRAINT chk_memory_extraction_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_memory_extraction_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_memory_extraction_version
        CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve already materialized v1 Memory. A legacy-id-derived semantic key avoids silently
-- dropping duplicate v1 decision keys; later consolidation can merge them with full evidence.
INSERT IGNORE INTO memory_item (
    memory_id, owner_key, scope_type, scope_key, chartbook_id,
    memory_type, semantic_key, title, canonical_text, status,
    confidence, evidence_count, is_explicit, policy_version,
    source_conversation_id, source_turn_id, source_diagram_id,
    version, activated_at, created_at, updated_at
)
SELECT
    memory_id, owner_key, 'CHARTBOOK', chartbook_id, chartbook_id,
    'PROJECT',
    CONCAT('legacy.', LEFT(SHA2(CONCAT(memory_id, ':', decision_key), 256), 32)),
    LEFT(decision_key, 160), canonical_text, status,
    1.0000, 1, 1, policy_version,
    source_conversation_id, source_turn_id, source_diagram_id,
    version,
    CASE WHEN status = 'ACTIVE' THEN updated_at ELSE NULL END,
    created_at, updated_at
FROM chartbook_memory
WHERE status IN ('ACTIVE', 'DISABLED');

INSERT IGNORE INTO memory_evidence (
    evidence_id, memory_id, owner_key,
    source_conversation_id, source_turn_id, source_diagram_id,
    observation_kind, observation_digest, observed_text, confidence,
    disposition, policy_version, observed_at, created_at
)
SELECT
    CONCAT('evidence_', LEFT(SHA2(CONCAT('legacy:', memory_id), 256), 40)),
    memory_id, owner_key,
    source_conversation_id, source_turn_id, source_diagram_id,
    'MIGRATED', SHA2(CONCAT('legacy:', memory_id, ':', declaration_digest), 256),
    canonical_text, 1.0000, 'SUPPORTING', policy_version, created_at, created_at
FROM chartbook_memory
WHERE status IN ('ACTIVE', 'DISABLED');
