package org.zipp.ai.domain.ingestion.model.valobj;

/**
 * Stable worker stages used in queue identity and persistence. The explicit vocabulary prevents
 * producers and workers from silently inventing incompatible stage names.
 */
public enum ProcessingJobStage {
    VALIDATE_OWNERSHIP,
    MALWARE_SCAN,
    STRUCTURE_VALIDATE,
    RESOLVE_CONTENT_DEDUP,
    MATERIALIZE_VERSION_AND_REVISION,
    PROMOTE_ORIGINAL,
    EXTRACT_NATIVE,
    OCR_SELECTED_PAGES,
    MERGE_NATIVE_AND_OCR_BLOCKS,
    NORMALIZE_CANONICAL_PAGES,
    BUILD_DOCUMENT_STRUCTURE,
    ANALYZE_VISUALS,
    BUILD_EVIDENCE_UNITS,
    BUILD_RETRIEVAL_CHUNKS,
    BUILD_LEXICAL_PROJECTION,
    EMBED_CHUNK_BATCHES,
    UPSERT_VECTOR_BATCHES,
    VERIFY_PROJECTION_MANIFEST,
    PUBLISH_REVISION,
    BUILD_COMPATIBILITY_PROJECTION
}
