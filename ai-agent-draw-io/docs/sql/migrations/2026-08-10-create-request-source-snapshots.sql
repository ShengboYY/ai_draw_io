CREATE TABLE IF NOT EXISTS request_source_snapshot (
    run_id VARCHAR(128) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    declaration_fingerprint CHAR(64) NOT NULL,
    source_mode VARCHAR(24) NOT NULL,
    processing_source_count INT NOT NULL DEFAULT 0,
    unavailable_source_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id),
    KEY idx_request_source_snapshot_owner (owner_type, owner_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS request_source_snapshot_item (
    run_id VARCHAR(128) NOT NULL,
    ordinal INT NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_key VARCHAR(128) NOT NULL,
    source_state VARCHAR(24) NOT NULL,
    source_origin VARCHAR(24) NOT NULL,
    has_text BOOLEAN NOT NULL DEFAULT FALSE,
    has_visual BOOLEAN NOT NULL DEFAULT FALSE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id, ordinal),
    UNIQUE KEY uk_request_source_snapshot_version (run_id, version_id),
    CONSTRAINT fk_request_source_snapshot_item_run
        FOREIGN KEY (run_id) REFERENCES request_source_snapshot(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
