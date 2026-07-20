-- WP7 answer citations retain per-run source provenance for durable history rendering.
ALTER TABLE citation_evidence
    ADD COLUMN source_origin VARCHAR(24) NULL AFTER use_role;
