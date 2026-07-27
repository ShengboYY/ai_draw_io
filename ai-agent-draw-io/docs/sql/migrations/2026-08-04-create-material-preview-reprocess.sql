-- WP4-B: separate full processing identity, Worker routing profile, and HTTP request idempotency.
ALTER TABLE material_processing_revision
    ADD COLUMN worker_profile_fingerprint CHAR(64) NULL AFTER fingerprint;

UPDATE material_processing_revision
SET worker_profile_fingerprint = fingerprint
WHERE worker_profile_fingerprint IS NULL;

-- Before WP4-B, excluded pages were not user-editable, so every existing revision has an empty set.
UPDATE material_processing_revision
SET fingerprint = SHA2(CONCAT(worker_profile_fingerprint, ':excluded='), 256);

ALTER TABLE material_processing_revision
    MODIFY COLUMN worker_profile_fingerprint CHAR(64) NOT NULL,
    ADD KEY idx_processing_revision_worker_profile (version_id, worker_profile_fingerprint);

CREATE TABLE IF NOT EXISTS material_reprocess_request (
    owner_key VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (owner_key, material_id, request_fingerprint),
    KEY idx_reprocess_request_revision (revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
