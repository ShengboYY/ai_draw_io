-- Versioned, owner-fenced project context for Chartbook.
-- Profile data is independent from the legacy Chartbook preferences field and is safe to project into Context.
USE ai_draw_io;

CREATE TABLE IF NOT EXISTS chartbook_profile (
    chartbook_id             VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    instructions             VARCHAR(8000) NOT NULL,
    goal                     VARCHAR(2000) NOT NULL,
    summary                  VARCHAR(4000) NOT NULL,
    glossary_json            JSON NOT NULL,
    default_style_json       JSON NOT NULL,
    stable_constraints_json  JSON NOT NULL,
    profile_state             VARCHAR(16) NOT NULL,
    version                   BIGINT NOT NULL DEFAULT 0,
    created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (chartbook_id),
    KEY idx_chartbook_profile_owner (owner_key, chartbook_id, version),
    CONSTRAINT fk_chartbook_profile_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_chartbook_profile_state
        CHECK (profile_state IN ('EMPTY', 'CONFIGURED')),
    CONSTRAINT chk_chartbook_profile_version
        CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chartbook_profile_version (
    chartbook_id             VARCHAR(64) NOT NULL,
    owner_key                VARCHAR(64) NOT NULL,
    version                   BIGINT NOT NULL,
    instructions             VARCHAR(8000) NOT NULL,
    goal                     VARCHAR(2000) NOT NULL,
    summary                  VARCHAR(4000) NOT NULL,
    glossary_json            JSON NOT NULL,
    default_style_json       JSON NOT NULL,
    stable_constraints_json  JSON NOT NULL,
    profile_state             VARCHAR(16) NOT NULL,
    updated_at                DATETIME(3) NOT NULL,
    created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (chartbook_id, version),
    KEY idx_chartbook_profile_version_owner (owner_key, chartbook_id, version),
    CONSTRAINT fk_chartbook_profile_version_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_chartbook_profile_version_state
        CHECK (profile_state IN ('EMPTY', 'CONFIGURED')),
    CONSTRAINT chk_chartbook_profile_version_number
        CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chartbook_profile_audit (
    id               VARCHAR(64) NOT NULL,
    owner_key        VARCHAR(64) NOT NULL,
    chartbook_id     VARCHAR(64) NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    version          BIGINT NOT NULL,
    content_digest   CHAR(64) NOT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chartbook_profile_audit_request (owner_key, chartbook_id, idempotency_key),
    KEY idx_chartbook_profile_audit_version (chartbook_id, version),
    CONSTRAINT fk_chartbook_profile_audit_chartbook
        FOREIGN KEY (chartbook_id) REFERENCES chartbook (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_chartbook_profile_audit_version
        CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing Chartbooks start with an explicit empty Profile and version zero.
INSERT IGNORE INTO chartbook_profile (
    chartbook_id, owner_key, instructions, goal, summary, glossary_json,
    default_style_json, stable_constraints_json, profile_state, version, created_at, updated_at
)
SELECT id, owner_key, '', '', '', JSON_OBJECT(), JSON_OBJECT(), JSON_ARRAY(), 'EMPTY', 0,
       created_at, updated_at
FROM chartbook;

INSERT IGNORE INTO chartbook_profile_version (
    chartbook_id, owner_key, version, instructions, goal, summary, glossary_json,
    default_style_json, stable_constraints_json, profile_state, updated_at, created_at
)
SELECT chartbook_id, owner_key, 0, instructions, goal, summary, glossary_json,
       default_style_json, stable_constraints_json, profile_state, updated_at, created_at
FROM chartbook_profile
WHERE version = 0;
