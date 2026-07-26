-- M6: durable hand-off between exact visual observation and Direct generation.
-- The row is an immutable, owner/turn/plan-fenced projection; source bytes remain in the
-- material artifact store and are never copied into this table.
CREATE TABLE IF NOT EXISTS turn_source_direct_preparation (
    owner_key             VARCHAR(64) NOT NULL,
    conversation_id       VARCHAR(64) NOT NULL,
    turn_id               VARCHAR(128) NOT NULL,
    prepared_ref          VARCHAR(255) NOT NULL,
    plan_fingerprint      CHAR(64) NOT NULL,
    source_snapshot_ref   VARCHAR(255) NOT NULL,
    observation_fingerprint VARCHAR(255) NOT NULL,
    canvas_xml             MEDIUMTEXT NOT NULL,
    created_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id, prepared_ref),
    KEY idx_turn_source_direct_preparation_lookup (
        owner_key, conversation_id, turn_id, plan_fingerprint
    ),
    CONSTRAINT fk_turn_source_direct_preparation_execution
        FOREIGN KEY (owner_key, conversation_id, turn_id)
        REFERENCES turn_execution (owner_key, conversation_id, turn_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Evidence is stored as bounded display data so a takeover can rebuild the model input without
-- retaining a lease or a JVM object graph. Source authorization remains the snapshot/binding.
CREATE TABLE IF NOT EXISTS turn_source_evidence_preparation (
    owner_key             VARCHAR(64) NOT NULL,
    conversation_id       VARCHAR(64) NOT NULL,
    turn_id               VARCHAR(128) NOT NULL,
    prepared_ref          VARCHAR(255) NOT NULL,
    plan_fingerprint      CHAR(64) NOT NULL,
    source_snapshot_ref   VARCHAR(255) NOT NULL,
    manifest_digest       CHAR(64) NOT NULL,
    evidence_json         MEDIUMTEXT NOT NULL,
    created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id, prepared_ref),
    KEY idx_turn_source_evidence_preparation_lookup (
        owner_key, conversation_id, turn_id, plan_fingerprint
    ),
    CONSTRAINT fk_turn_source_evidence_preparation_execution
        FOREIGN KEY (owner_key, conversation_id, turn_id)
        REFERENCES turn_execution (owner_key, conversation_id, turn_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
