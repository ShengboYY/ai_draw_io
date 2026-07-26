-- M5 source-aware commit bindings, durable clarification authority and Direct provenance.
-- This migration is additive and idempotent.

CREATE TABLE IF NOT EXISTS turn_source_execution_binding (
    owner_key               VARCHAR(64) NOT NULL,
    conversation_id         VARCHAR(64) NOT NULL,
    turn_id                 VARCHAR(128) NOT NULL,
    plan_fingerprint        CHAR(64) NOT NULL,
    source_snapshot_ref     VARCHAR(255) NOT NULL,
    snapshot_binding_digest CHAR(64) NOT NULL,
    execution_entry_id      CHAR(64) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id),
    CONSTRAINT fk_source_execution_binding_turn
        FOREIGN KEY (owner_key, conversation_id, turn_id)
        REFERENCES turn_execution (owner_key, conversation_id, turn_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS turn_clarification (
    owner_key          VARCHAR(64) NOT NULL,
    conversation_id    VARCHAR(64) NOT NULL,
    turn_id            VARCHAR(128) NOT NULL,
    clarification_id   VARCHAR(128) NOT NULL,
    safe_message       VARCHAR(500) NOT NULL,
    option_set_digest  CHAR(64) NOT NULL,
    expires_at         DATETIME(3) NOT NULL,
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id, clarification_id),
    UNIQUE KEY uk_turn_clarification_authority (
        owner_key, conversation_id, clarification_id
    ),
    KEY idx_turn_clarification_lookup (
        owner_key, conversation_id, clarification_id, expires_at
    ),
    CONSTRAINT fk_turn_clarification_execution
        FOREIGN KEY (owner_key, conversation_id, turn_id)
        REFERENCES turn_execution (owner_key, conversation_id, turn_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS turn_clarification_option (
    owner_key               VARCHAR(64) NOT NULL,
    conversation_id         VARCHAR(64) NOT NULL,
    turn_id                 VARCHAR(128) NOT NULL,
    clarification_id        VARCHAR(128) NOT NULL,
    option_id               VARCHAR(128) NOT NULL,
    option_ordinal          INT NOT NULL,
    safe_label              VARCHAR(255) NOT NULL,
    candidate_ref           VARCHAR(255) NOT NULL,
    candidate_origin        VARCHAR(32) NOT NULL,
    observation_fingerprint VARCHAR(255) NOT NULL,
    clarification_ref       VARCHAR(255) NOT NULL,
    lineage_fingerprint     CHAR(64) NOT NULL,
    declaration_digest      CHAR(64) NOT NULL,
    context_read_set_digest CHAR(64) NOT NULL,
    input_binding_digest    CHAR(64) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (
        owner_key, conversation_id, turn_id, clarification_id, option_id
    ),
    UNIQUE KEY uk_turn_clarification_option_order (
        owner_key, conversation_id, turn_id, clarification_id, option_ordinal
    ),
    CONSTRAINT fk_turn_clarification_option_header
        FOREIGN KEY (owner_key, conversation_id, turn_id, clarification_id)
        REFERENCES turn_clarification (
            owner_key, conversation_id, turn_id, clarification_id
        )
        ON DELETE CASCADE,
    CONSTRAINT chk_turn_clarification_candidate_origin CHECK (
        candidate_origin IN (
            'CURRENT_MESSAGE_ATTACHMENT', 'NAMED_SOURCE',
            'CONFIRMED_CLARIFICATION'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagram_visual_provenance (
    diagram_id              VARCHAR(64) NOT NULL,
    canvas_version          BIGINT NOT NULL,
    provenance_ref          VARCHAR(255) NOT NULL,
    source_identity_ref     VARCHAR(255) NOT NULL,
    candidate_origin        VARCHAR(32) NOT NULL,
    observation_fingerprint VARCHAR(255) NOT NULL,
    source_snapshot_ref     VARCHAR(255) NOT NULL,
    plan_fingerprint        CHAR(64) NOT NULL,
    execution_entry_id      CHAR(64) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (diagram_id, canvas_version),
    UNIQUE KEY uk_visual_provenance_ref (diagram_id, provenance_ref),
    CONSTRAINT fk_visual_provenance_diagram
        FOREIGN KEY (diagram_id) REFERENCES diagram (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS direct_source_usage_pin (
    owner_key               VARCHAR(64) NOT NULL,
    source_identity_ref     VARCHAR(255) NOT NULL,
    source_snapshot_ref     VARCHAR(255) NOT NULL,
    snapshot_binding_digest CHAR(64) NOT NULL,
    state                   VARCHAR(16) NOT NULL,
    first_used_at           DATETIME(3) NOT NULL,
    latest_used_at          DATETIME(3) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, source_identity_ref, source_snapshot_ref),
    KEY idx_direct_source_usage_snapshot (
        owner_key, source_snapshot_ref, state
    ),
    CONSTRAINT chk_direct_source_usage_pin_state CHECK (
        state IN ('ACTIVE', 'SUPERSEDED', 'REVOKED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
