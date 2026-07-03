-- ============================================================================
-- Persistent counters for account rate limits and AI quota enforcement.
--
-- Counter subjects (email, IP, user/workspace IDs) are never stored directly.
-- The application hashes the scoped counter key with SHA-256 and stores only
-- the hash plus a short hash preview for support/debugging.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS usage_counter (
    counter_key_hash    CHAR(64)    NOT NULL COMMENT 'SHA-256(scoped counter key); raw subject is not stored',
    counter_key_preview VARCHAR(16)  NOT NULL COMMENT 'First 16 chars of hash for support/debugging',
    counter_type        VARCHAR(24)  NOT NULL COMMENT 'COUNTER | FAILURE',
    count_value         INT          NOT NULL DEFAULT 0 COMMENT 'Current persisted count',
    expires_at          DATETIME     NOT NULL COMMENT 'Counter expiry; expired rows are treated as zero',
    locked_until        DATETIME     NULL COMMENT 'Failure lock expiry when counter_type=FAILURE',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (counter_key_hash),
    KEY idx_usage_counter_expires (expires_at),
    KEY idx_usage_counter_type_expires (counter_type, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '持久化限流/额度计数器';
