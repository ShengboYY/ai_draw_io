-- WP3C-B3b2: retrieval workers and readers must address immutable S3 object versions.
ALTER TABLE retrieval_chunk
    ADD COLUMN retrieval_text_object_version_id VARCHAR(255) NOT NULL
        AFTER retrieval_text_object_key,
    ADD COLUMN parent_context_object_version_id VARCHAR(255) NULL
        AFTER parent_context_object_key,
    ADD CONSTRAINT chk_retrieval_parent_artifact_pin
        CHECK ((parent_context_object_key IS NULL AND parent_context_object_version_id IS NULL)
            OR (parent_context_object_key IS NOT NULL AND parent_context_object_version_id IS NOT NULL));

-- Mapping ordinal is the stable projection identity; one Evidence unit can fill distinct roles.
ALTER TABLE retrieval_chunk_evidence
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (retrieval_chunk_id, ordinal),
    ADD KEY idx_chunk_evidence_identity (retrieval_chunk_id, evidence_id);
