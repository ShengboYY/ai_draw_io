-- M1 additive foundation for canonical conversations and durable turn control.
-- Existing session-based messages are intentionally not backfilled here; M3 owns
-- historical scope migration and dual-read compatibility.

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(64) NOT NULL,
    owner_key   VARCHAR(64) NOT NULL,
    diagram_id  VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conversation_owner_diagram (owner_key, diagram_id, status, updated_at),
    CONSTRAINT fk_conversation_diagram
        FOREIGN KEY (diagram_id) REFERENCES diagram (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_conversation_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_legacy_alias (
    owner_key           VARCHAR(64) NOT NULL,
    conversation_id     VARCHAR(64) NOT NULL,
    legacy_session_id   VARCHAR(128) NOT NULL,
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, legacy_session_id),
    UNIQUE KEY uk_conversation_legacy_alias (conversation_id, legacy_session_id),
    KEY idx_conversation_alias_conversation (conversation_id),
    CONSTRAINT fk_conversation_alias_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Keep the legacy message writer valid while reserving the server-owned fields
-- needed by the M1 start-commit invariant.
SET @message_conversation_id_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND COLUMN_NAME = 'conversation_id'
);
SET @add_message_conversation_id_sql = IF(
    @message_conversation_id_count = 0,
    'ALTER TABLE diagram_conversation_message ADD COLUMN conversation_id VARCHAR(64) NULL AFTER diagram_id',
    'SET @message_conversation_id_noop = 1'
);
PREPARE add_message_conversation_id_stmt FROM @add_message_conversation_id_sql;
EXECUTE add_message_conversation_id_stmt;
DEALLOCATE PREPARE add_message_conversation_id_stmt;

SET @message_turn_id_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND COLUMN_NAME = 'turn_id'
);
SET @add_message_turn_id_sql = IF(
    @message_turn_id_count = 0,
    'ALTER TABLE diagram_conversation_message ADD COLUMN turn_id VARCHAR(128) NULL AFTER session_id',
    'SET @message_turn_id_noop = 1'
);
PREPARE add_message_turn_id_stmt FROM @add_message_turn_id_sql;
EXECUTE add_message_turn_id_stmt;
DEALLOCATE PREPARE add_message_turn_id_stmt;

SET @message_sequence_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND COLUMN_NAME = 'message_sequence'
);
SET @add_message_sequence_sql = IF(
    @message_sequence_count = 0,
    'ALTER TABLE diagram_conversation_message ADD COLUMN message_sequence BIGINT NULL AFTER turn_id',
    'SET @message_sequence_noop = 1'
);
PREPARE add_message_sequence_stmt FROM @add_message_sequence_sql;
EXECUTE add_message_sequence_stmt;
DEALLOCATE PREPARE add_message_sequence_stmt;

SET @message_status_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND COLUMN_NAME = 'message_status'
);
SET @add_message_status_sql = IF(
    @message_status_count = 0,
    'ALTER TABLE diagram_conversation_message ADD COLUMN message_status VARCHAR(24) NOT NULL DEFAULT ''COMMITTED'' AFTER content',
    'SET @message_status_noop = 1'
);
PREPARE add_message_status_stmt FROM @add_message_status_sql;
EXECUTE add_message_status_stmt;
DEALLOCATE PREPARE add_message_status_stmt;

SET @message_user_role_guard_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND COLUMN_NAME = 'user_role_guard'
);
SET @add_message_user_role_guard_sql = IF(
    @message_user_role_guard_count = 0,
    'ALTER TABLE diagram_conversation_message ADD COLUMN user_role_guard TINYINT GENERATED ALWAYS AS (CASE WHEN LOWER(role) = ''user'' THEN 1 ELSE NULL END) STORED AFTER message_status',
    'SET @message_user_role_guard_noop = 1'
);
PREPARE add_message_user_role_guard_stmt FROM @add_message_user_role_guard_sql;
EXECUTE add_message_user_role_guard_stmt;
DEALLOCATE PREPARE add_message_user_role_guard_stmt;

SET @message_conversation_fk_count = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND CONSTRAINT_NAME = 'fk_message_conversation'
);
SET @add_message_conversation_fk_sql = IF(
    @message_conversation_fk_count = 0,
    'ALTER TABLE diagram_conversation_message ADD CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE SET NULL',
    'SET @message_conversation_fk_noop = 1'
);
PREPARE add_message_conversation_fk_stmt FROM @add_message_conversation_fk_sql;
EXECUTE add_message_conversation_fk_stmt;
DEALLOCATE PREPARE add_message_conversation_fk_stmt;

SET @message_turn_index_count = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND INDEX_NAME = 'uk_message_conversation_turn_user'
);
SET @add_message_turn_index_sql = IF(
    @message_turn_index_count = 0,
    'ALTER TABLE diagram_conversation_message ADD UNIQUE KEY uk_message_conversation_turn_user (conversation_id, turn_id, user_role_guard)',
    'SET @message_turn_index_noop = 1'
);
PREPARE add_message_turn_index_stmt FROM @add_message_turn_index_sql;
EXECUTE add_message_turn_index_stmt;
DEALLOCATE PREPARE add_message_turn_index_stmt;

SET @message_sequence_index_count = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'diagram_conversation_message'
      AND INDEX_NAME = 'uk_message_conversation_sequence'
);
SET @add_message_sequence_index_sql = IF(
    @message_sequence_index_count = 0,
    'ALTER TABLE diagram_conversation_message ADD UNIQUE KEY uk_message_conversation_sequence (conversation_id, message_sequence)',
    'SET @message_sequence_index_noop = 1'
);
PREPARE add_message_sequence_index_stmt FROM @add_message_sequence_index_sql;
EXECUTE add_message_sequence_index_stmt;
DEALLOCATE PREPARE add_message_sequence_index_stmt;

CREATE TABLE IF NOT EXISTS turn_engine_migration_state (
    state_name              VARCHAR(32) NOT NULL,
    generation              BIGINT NOT NULL DEFAULT 0,
    mode                    VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    switched_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    retry_horizon_ends_at   DATETIME(3) NULL,
    tombstone_retain_until  DATETIME(3) NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (state_name),
    CONSTRAINT chk_turn_migration_state_name CHECK (state_name = 'DEFAULT'),
    CONSTRAINT chk_turn_migration_mode CHECK (mode IN ('LEGACY', 'V2_CANARY', 'ALL_V2', 'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO turn_engine_migration_state (state_name, generation, mode)
VALUES ('DEFAULT', 0, 'LEGACY');

CREATE TABLE IF NOT EXISTS turn_engine_assignment (
    owner_key                         VARCHAR(64) NOT NULL,
    conversation_id                   VARCHAR(64) NOT NULL,
    diagram_id                        VARCHAR(64) NOT NULL,
    turn_id                           VARCHAR(128) NOT NULL,
    request_fingerprint_schema_version INT NOT NULL,
    request_fingerprint               VARCHAR(128) NOT NULL,
    selected_engine                   VARCHAR(16) NOT NULL,
    migration_generation              BIGINT NOT NULL,
    migration_mode                    VARCHAR(24) NOT NULL,
    execution_policy_schema_version  INT NOT NULL,
    execution_policy_snapshot_json    JSON NOT NULL,
    execution_policy_hash             CHAR(64) NOT NULL,
    memory_write_schema_version       INT NULL,
    memory_write_declaration_json     JSON NULL,
    memory_write_digest                CHAR(64) NULL,
    legacy_routing_kind               VARCHAR(16) NULL,
    legacy_routing_reason             VARCHAR(128) NULL,
    legacy_retry_policy_version       VARCHAR(64) NULL,
    legacy_retry_eligible_until       DATETIME(3) NULL,
    legacy_retirement_state           VARCHAR(16) NULL,
    legacy_archived_at                DATETIME(3) NULL,
    created_at                        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id),
    KEY idx_turn_assignment_engine (selected_engine, created_at),
    KEY idx_turn_assignment_legacy_retirement (selected_engine, legacy_retirement_state, legacy_retry_eligible_until),
    CONSTRAINT fk_turn_assignment_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_turn_assignment_engine CHECK (selected_engine IN ('LEGACY', 'V2')),
    CONSTRAINT chk_turn_assignment_mode CHECK (migration_mode IN ('LEGACY', 'V2_CANARY', 'ALL_V2', 'RETIRED')),
    CONSTRAINT chk_turn_assignment_retirement CHECK (
        selected_engine = 'V2'
        OR legacy_retirement_state IN ('EXECUTABLE', 'EXPIRED_GONE', 'ARCHIVED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS legacy_turn_tombstone (
    owner_key               VARCHAR(64) NOT NULL,
    conversation_id         VARCHAR(64) NOT NULL,
    diagram_id              VARCHAR(64) NOT NULL,
    turn_id                 VARCHAR(128) NOT NULL,
    migration_generation    BIGINT NOT NULL,
    reason                  VARCHAR(32) NOT NULL,
    retain_until            DATETIME(3) NOT NULL,
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, conversation_id, turn_id),
    KEY idx_turn_tombstone_retention (migration_generation, retain_until),
    CONSTRAINT chk_turn_tombstone_reason CHECK (reason = 'LEGACY_RETRY_EXPIRED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS turn_execution (
    owner_key                         VARCHAR(64) NOT NULL,
    conversation_id                   VARCHAR(64) NOT NULL,
    diagram_id                        VARCHAR(64) NOT NULL,
    turn_id                           VARCHAR(128) NOT NULL,
    current_attempt_id               VARCHAR(128) NULL,
    attempt_epoch                    BIGINT NOT NULL DEFAULT 0,
    lease_policy_version             VARCHAR(64) NULL,
    lease_ttl_ms                     BIGINT NULL,
    lease_expires_at                 DATETIME(3) NULL,
    last_heartbeat_at                DATETIME(3) NULL,
    request_message_id               BIGINT NULL,
    request_fingerprint_schema_version INT NOT NULL,
    request_fingerprint              VARCHAR(128) NOT NULL,
    migration_generation             BIGINT NOT NULL,
    migration_mode                   VARCHAR(24) NOT NULL,
    execution_policy_schema_version  INT NOT NULL,
    execution_policy_snapshot_json   JSON NOT NULL,
    execution_policy_hash            CHAR(64) NOT NULL,
    turn_input_binding_schema_version INT NULL,
    turn_input_binding_digest        CHAR(64) NULL,
    attachment_binding_digest        CHAR(64) NULL,
    memory_write_schema_version      INT NULL,
    memory_write_declaration_json    JSON NULL,
    memory_write_digest              CHAR(64) NULL,
    response_message_id              BIGINT NULL,
    context_message_high_water       BIGINT NULL,
    context_read_set_schema_version  INT NULL,
    context_read_set_json            JSON NULL,
    context_read_set_digest          CHAR(64) NULL,
    context_read_set_pinned_at       DATETIME(3) NULL,
    plan_payload_schema_version      INT NULL,
    plan_payload_json                JSON NULL,
    plan_payload_digest              CHAR(64) NULL,
    plan_pinned_at                   DATETIME(3) NULL,
    status                            VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    terminal_code                    VARCHAR(64) NULL,
    terminal_payload_type            VARCHAR(64) NULL,
    terminal_payload_schema_version INT NULL,
    terminal_payload_ref             VARCHAR(255) NULL,
    terminal_payload_json            JSON NULL,
    cancelled_at                     DATETIME(3) NULL,
    cancel_reason                    VARCHAR(255) NULL,
    canvas_version_before            BIGINT NULL,
    canvas_version_after             BIGINT NULL,
    source_snapshot_ref              VARCHAR(255) NULL,
    context_receipt_json             JSON NULL,
    created_at                       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    completed_at                     DATETIME(3) NULL,
    PRIMARY KEY (owner_key, conversation_id, turn_id),
    KEY idx_turn_execution_status_lease (status, lease_expires_at),
    KEY idx_turn_execution_attempt (current_attempt_id, attempt_epoch),
    CONSTRAINT fk_turn_execution_assignment
        FOREIGN KEY (owner_key, conversation_id, turn_id)
        REFERENCES turn_engine_assignment (owner_key, conversation_id, turn_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_turn_execution_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED', 'EXPIRED_GONE', 'ORPHANED_RETRYABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_message_attachment (
    owner_key                  VARCHAR(64) NOT NULL,
    conversation_id            VARCHAR(64) NOT NULL,
    message_id                 BIGINT NOT NULL,
    turn_id                    VARCHAR(128) NOT NULL,
    attachment_order           INT NOT NULL,
    conversation_file_ref      VARCHAR(128) NOT NULL,
    attachment_status_at_bind  VARCHAR(24) NOT NULL,
    created_at                 DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (message_id, attachment_order),
    UNIQUE KEY uk_message_attachment_ref (message_id, conversation_file_ref),
    KEY idx_attachment_owner_scope (owner_key, conversation_id, turn_id),
    CONSTRAINT fk_attachment_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversation (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_attachment_message
        FOREIGN KEY (message_id) REFERENCES diagram_conversation_message (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
