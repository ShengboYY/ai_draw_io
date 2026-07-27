CREATE TABLE IF NOT EXISTS anonymous_workspace (
    owner_id VARCHAR(64) NOT NULL,
    credential_id VARCHAR(64) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    claimed_by_user_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    claimed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_id),
    UNIQUE KEY uk_anonymous_workspace_credential_id (credential_id),
    KEY idx_anonymous_workspace_claimed_by (claimed_by_user_id),
    KEY idx_anonymous_workspace_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
