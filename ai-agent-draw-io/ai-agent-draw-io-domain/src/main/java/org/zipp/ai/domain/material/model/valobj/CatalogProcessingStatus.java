package org.zipp.ai.domain.material.model.valobj;

import java.util.Locale;

/** Stable user-facing processing status derived from mutable internal revision state. */
public enum CatalogProcessingStatus {
    EXTRACTING,
    OCR_VISUAL,
    INDEXING,
    READY,
    PARTIAL_READY,
    FAILED;

    public static CatalogProcessingStatus from(String ingestState, String revisionState,
                                               String revisionStage) {
        String state = normalized(revisionState);
        if ("READY".equals(state)) return READY;
        if ("PARTIAL_READY".equals(state)) return PARTIAL_READY;
        if ("FAILED".equals(state) || "REJECTED".equals(state)) return FAILED;

        String stage = normalized(revisionStage);
        if ("PROCESSING".equals(state)) {
            // A reprocess keeps the old version READY, so the current revision must win completely.
            if ("OCR_VISUAL".equals(stage) || "VISUAL_ANALYSIS".equals(stage)) return OCR_VISUAL;
            if ("EVIDENCE_BUILD".equals(stage) || "INDEXING".equals(stage)
                    || "PUBLISHING".equals(stage) || "EMBEDDING".equals(stage)) return INDEXING;
            return EXTRACTING;
        }

        String ingest = normalized(ingestState);
        if ("READY".equals(ingest)) return READY;
        if ("FAILED".equals(ingest) || "REJECTED".equals(ingest)) return FAILED;
        return EXTRACTING;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
