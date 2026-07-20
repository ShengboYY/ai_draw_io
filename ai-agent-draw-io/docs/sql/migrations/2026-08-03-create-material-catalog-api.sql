-- WP4-A: idempotent chartbook creation for the owner-scoped catalog API.
ALTER TABLE chartbook
    ADD COLUMN create_idempotency_key VARCHAR(128) NULL AFTER preferences_json,
    ADD UNIQUE KEY uk_chartbook_owner_idempotency (owner_key, create_idempotency_key);
