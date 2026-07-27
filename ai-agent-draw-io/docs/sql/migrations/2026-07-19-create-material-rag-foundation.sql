-- WP1 additive foundation for the material, ingestion, retrieval, citation, and chartbook contexts.
-- Content objects remain in S3; Pinecone stores only vectors and opaque metadata.

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS material (
    id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    display_name VARCHAR(512) NOT NULL,
    retention_class VARCHAR(16) NOT NULL,
    origin_conversation_id VARCHAR(128) NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    lifecycle_generation BIGINT NOT NULL DEFAULT 0,
    latest_version_id VARCHAR(64) NULL,
    last_meaningful_activity_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    trash_expires_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_material_owner_state (owner_type, owner_key, lifecycle_state),
    KEY idx_material_ttl (retention_class, lifecycle_state, expires_at),
    CONSTRAINT chk_material_retention CHECK (
        (retention_class = 'TEMPORARY' AND origin_conversation_id IS NOT NULL
            AND (lifecycle_state <> 'ACTIVE' OR expires_at IS NOT NULL))
        OR (retention_class = 'RETAINED' AND expires_at IS NULL)
    ),
    CONSTRAINT chk_anonymous_material_retention CHECK (owner_type <> 'ANONYMOUS' OR retention_class = 'TEMPORARY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_tag (
    material_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    normalized_tag VARCHAR(128) NOT NULL,
    display_tag VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (material_id, normalized_tag),
    KEY idx_material_tag_owner (owner_key, normalized_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_content_blob (
    id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    detected_mime VARCHAR(128) NOT NULL,
    original_object_key VARCHAR(1024) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_blob_owner_hash (owner_type, owner_key, content_sha256, byte_size)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_version (
    id VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    content_blob_id VARCHAR(64) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    declared_mime VARCHAR(128) NULL,
    detected_mime VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL,
    page_count INT NULL,
    active_revision_id VARCHAR(64) NULL,
    ingest_state VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_version_no (material_id, version_no),
    UNIQUE KEY uk_material_version_blob (material_id, content_blob_id),
    KEY idx_material_version_owner (owner_key, material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_upload_session (
    id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(512) NULL,
    target_scope_type VARCHAR(24) NOT NULL,
    target_scope_key VARCHAR(160) NOT NULL,
    target_retention_class VARCHAR(16) NOT NULL,
    new_version_of_material_id VARCHAR(64) NULL,
    expected_size BIGINT NOT NULL,
    expected_sha256 CHAR(64) NULL,
    declared_mime VARCHAR(128) NULL,
    quarantine_bucket VARCHAR(255) NULL,
    quarantine_key VARCHAR(1024) NULL,
    -- Hash the complete S3 key because an utf8mb4 VARCHAR(1024) exceeds MySQL's 3072-byte index limit.
    quarantine_key_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(quarantine_key, 256))) STORED,
    quarantine_object_version_id VARCHAR(255) NULL,
    s3_etag VARCHAR(255) NULL,
    s3_checksum_sha256 VARCHAR(128) NULL,
    policy_expires_at DATETIME(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    material_id VARCHAR(64) NULL,
    version_id VARCHAR(64) NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_upload_owner_idempotency (owner_type, owner_key, idempotency_key),
    UNIQUE KEY uk_material_upload_quarantine_key (quarantine_key_hash),
    KEY idx_material_upload_state_expiry (state, policy_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_scope_link (
    id VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_key VARCHAR(160) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_scope (material_id, scope_type, scope_key),
    KEY idx_material_scope_lookup (scope_type, scope_key, material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chartbook (
    id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    preferences_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_chartbook_owner (owner_key, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @diagram_chartbook_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'diagram' AND COLUMN_NAME = 'chartbook_id'
);
SET @add_diagram_chartbook_column_sql = IF(
    @diagram_chartbook_column_count = 0,
    'ALTER TABLE diagram ADD COLUMN chartbook_id VARCHAR(64) NULL AFTER diagram_type',
    'SET @diagram_chartbook_column_noop = 1'
);
PREPARE add_diagram_chartbook_column_stmt FROM @add_diagram_chartbook_column_sql;
EXECUTE add_diagram_chartbook_column_stmt;
DEALLOCATE PREPARE add_diagram_chartbook_column_stmt;

SET @diagram_chartbook_index_count = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'diagram' AND INDEX_NAME = 'idx_diagram_chartbook'
);
SET @add_diagram_chartbook_index_sql = IF(
    @diagram_chartbook_index_count = 0,
    'ALTER TABLE diagram ADD KEY idx_diagram_chartbook (chartbook_id)',
    'SET @diagram_chartbook_index_noop = 1'
);
PREPARE add_diagram_chartbook_index_stmt FROM @add_diagram_chartbook_index_sql;
EXECUTE add_diagram_chartbook_index_stmt;
DEALLOCATE PREPARE add_diagram_chartbook_index_stmt;

CREATE TABLE IF NOT EXISTS diagram_source_pin (
    diagram_id VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    processing_revision_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    first_used_at DATETIME(3) NOT NULL,
    latest_citation_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (diagram_id, material_id, version_id, processing_revision_id),
    KEY idx_diagram_source_pin_material (material_id, version_id, processing_revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_source_context (
    conversation_id VARCHAR(128) NOT NULL,
    diagram_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    source_mode VARCHAR(24) NOT NULL,
    auto_library TINYINT(1) NOT NULL DEFAULT 1,
    selected_version_ids_json JSON NULL,
    pending_upload_ids_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (conversation_id),
    KEY idx_conversation_source_owner (owner_key, diagram_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS visual_processing_consent (
    id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_key VARCHAR(160) NOT NULL,
    provider_policy_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_visual_consent_scope (owner_key, scope_type, scope_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_processing_revision (
    id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_no INT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    parser_version VARCHAR(64) NOT NULL,
    cleaner_version VARCHAR(64) NOT NULL,
    chunk_schema_version VARCHAR(64) NOT NULL,
    ocr_version VARCHAR(64) NULL,
    vlm_schema_version VARCHAR(64) NULL,
    excluded_pages_json JSON NULL,
    gap_manifest_key VARCHAR(1024) NULL,
    fence_generation BIGINT NOT NULL DEFAULT 0,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_revision_no (version_id, revision_no),
    UNIQUE KEY uk_processing_revision_fingerprint (version_id, fingerprint),
    KEY idx_processing_revision_state (state, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_page (
    id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    page_no INT NOT NULL,
    width DECIMAL(12,4) NOT NULL,
    height DECIMAL(12,4) NOT NULL,
    native_text_status VARCHAR(24) NOT NULL,
    ocr_status VARCHAR(24) NOT NULL,
    ocr_quality DECIMAL(6,5) NULL,
    visual_status VARCHAR(24) NOT NULL,
    page_image_key VARCHAR(1024) NULL,
    raw_extraction_key VARCHAR(1024) NULL,
    canonical_page_key VARCHAR(1024) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_page_revision_no (revision_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_section (
    id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    parent_section_id VARCHAR(64) NULL,
    level INT NOT NULL,
    ordinal INT NOT NULL,
    page_start INT NOT NULL,
    page_end INT NOT NULL,
    heading_evidence_id VARCHAR(64) NULL,
    structure_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_section_ordinal (revision_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS evidence_unit (
    id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NOT NULL,
    section_id VARCHAR(64) NULL,
    unit_type VARCHAR(32) NOT NULL,
    modality VARCHAR(16) NOT NULL,
    source_channel VARCHAR(16) NOT NULL,
    display_text_object_key VARCHAR(1024) NULL,
    visual_object_key VARCHAR(1024) NULL,
    visual_analysis_object_key VARCHAR(1024) NULL,
    display_text_sha256 CHAR(64) NULL,
    quality_json JSON NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_evidence_revision_page (revision_id, page_id, status),
    CONSTRAINT chk_evidence_source CHECK (source_channel IN ('NATIVE', 'OCR', 'VISUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS evidence_region (
    evidence_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    bbox_json JSON NOT NULL,
    display_char_start INT NULL,
    display_char_end INT NULL,
    source_block_ref VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (evidence_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS evidence_relation (
    from_evidence_id VARCHAR(64) NOT NULL,
    to_evidence_id VARCHAR(64) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    weight DECIMAL(7,6) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (from_evidence_id, to_evidence_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_chunk (
    id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NULL,
    section_id VARCHAR(64) NULL,
    chunk_type VARCHAR(32) NOT NULL,
    modality VARCHAR(16) NOT NULL,
    language_primary VARCHAR(16) NULL,
    citable TINYINT(1) NOT NULL,
    index_mode VARCHAR(24) NOT NULL,
    duplicate_cluster_id VARCHAR(64) NULL,
    canonical_chunk_id VARCHAR(64) NULL,
    retrieval_text_object_key VARCHAR(1024) NOT NULL,
    retrieval_text_sha256 CHAR(64) NOT NULL,
    parent_context_object_key VARCHAR(1024) NULL,
    token_count INT NOT NULL,
    quality_score DECIMAL(7,6) NOT NULL,
    structural_ordinal INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_retrieval_chunk_revision (revision_id, status, structural_ordinal),
    KEY idx_retrieval_chunk_duplicate (revision_id, duplicate_cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_chunk_evidence (
    retrieval_chunk_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    role VARCHAR(24) NOT NULL,
    ordinal INT NOT NULL,
    char_start INT NULL,
    char_end INT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (retrieval_chunk_id, evidence_id),
    KEY idx_chunk_evidence_role (retrieval_chunk_id, role, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_search_document (
    retrieval_chunk_id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    word_search_text LONGTEXT NULL,
    cjk_search_text LONGTEXT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (retrieval_chunk_id),
    KEY idx_retrieval_search_auth (owner_type, owner_key, version_id, revision_id, status),
    FULLTEXT KEY ft_word (word_search_text),
    FULLTEXT KEY ft_cjk (cjk_search_text) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_exact_term (
    retrieval_chunk_id VARCHAR(64) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    normalized_term VARCHAR(255) NOT NULL,
    term_type VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (retrieval_chunk_id, normalized_term, term_type),
    KEY idx_exact_term (owner_type, owner_key, normalized_term, version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_index_generation (
    id VARCHAR(64) NOT NULL,
    index_name VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimension INT NOT NULL,
    metric VARCHAR(16) NOT NULL,
    vector_schema_version VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    activated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_rag_index_generation_state (state, activated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_chunk_vector_projection (
    retrieval_chunk_id VARCHAR(64) NOT NULL,
    index_generation_id VARCHAR(64) NOT NULL,
    index_name VARCHAR(128) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    vector_id VARCHAR(160) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_fingerprint CHAR(64) NOT NULL,
    dimension INT NOT NULL,
    projection_role VARCHAR(24) NOT NULL,
    state VARCHAR(16) NOT NULL,
    indexed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_chunk_projection (retrieval_chunk_id, index_generation_id),
    UNIQUE KEY uk_vector_projection_id (index_name, namespace, vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS material_processing_job (
    id VARCHAR(64) NOT NULL,
    upload_session_id VARCHAR(64) NULL,
    revision_id VARCHAR(64) NULL,
    stage VARCHAR(48) NOT NULL,
    work_key VARCHAR(160) NOT NULL DEFAULT 'root',
    input_fingerprint CHAR(64) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    not_before DATETIME(3) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(3) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    payload_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_work (upload_session_id, stage, work_key),
    UNIQUE KEY uk_revision_work (revision_id, stage, work_key),
    KEY idx_processing_job_claim (status, not_before, priority, created_at),
    CHECK ((upload_session_id IS NULL) <> (revision_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS deletion_task (
    id VARCHAR(64) NOT NULL,
    material_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NULL,
    stage VARCHAR(48) NOT NULL,
    status VARCHAR(16) NOT NULL,
    not_before DATETIME(3) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(3) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_deletion_task_claim (status, not_before, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS evidence_read_lease (
    id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    max_expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_evidence_lease_expiry (version_id, status, expires_at),
    KEY idx_evidence_lease_run (run_id, owner_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS grounded_run_control (
    run_id VARCHAR(128) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    cancelled_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_grounded_run_request (owner_key, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagram_canvas_version (
    diagram_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    content_hash VARCHAR(80) NULL,
    canvas_xml LONGTEXT NOT NULL,
    mutation_origin VARCHAR(24) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    run_id VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (diagram_id, version),
    KEY idx_canvas_version_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO diagram_canvas_version
    (diagram_id, version, content_hash, canvas_xml, mutation_origin, created_by, run_id, created_at, updated_at)
SELECT diagram_id, version, content_hash, current_xml, 'BASELINE', user_id, NULL, created_at, updated_at
FROM diagram_canvas_state;

CREATE TABLE IF NOT EXISTS source_citation (
    id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    diagram_id VARCHAR(64) NULL,
    canvas_version BIGINT NULL,
    cell_id VARCHAR(255) NULL,
    message_id VARCHAR(128) NULL,
    claim_key VARCHAR(128) NULL,
    support_type VARCHAR(24) NOT NULL,
    state VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_source_citation_diagram (owner_key, diagram_id, canvas_version),
    KEY idx_source_citation_message (owner_key, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS citation_evidence (
    citation_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    revision_id VARCHAR(64) NOT NULL,
    citation_key VARCHAR(128) NOT NULL,
    use_role VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (citation_id, evidence_id),
    KEY idx_citation_evidence_source (evidence_id, version_id, revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS citation_source_tombstone (
    id VARCHAR(64) NOT NULL,
    citation_id VARCHAR(64) NOT NULL,
    opaque_material_id VARCHAR(64) NOT NULL,
    opaque_version_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    deleted_at DATETIME(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_citation_tombstone_citation (citation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagram_cell_provenance (
    diagram_id VARCHAR(64) NOT NULL,
    cell_id VARCHAR(255) NOT NULL,
    canvas_version BIGINT NOT NULL,
    semantic_hash CHAR(64) NOT NULL,
    support_type VARCHAR(24) NOT NULL,
    current_citation_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (diagram_id, cell_id, canvas_version),
    KEY idx_cell_provenance_citation (current_citation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS deleted_source_tombstone (
    material_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    deleted_at DATETIME(3) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    former_citation_count BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (material_id, version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
