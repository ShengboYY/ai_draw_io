-- WP5: exact online hydration needs a complete immutable S3 identity, not only key/version/hash.
-- Existing chunks stay ineligible until reprocessing republishes their byte size and content type.
ALTER TABLE retrieval_chunk
    ADD COLUMN retrieval_text_byte_size BIGINT NULL AFTER retrieval_text_sha256,
    ADD COLUMN retrieval_text_content_type VARCHAR(128) NULL AFTER retrieval_text_byte_size;
