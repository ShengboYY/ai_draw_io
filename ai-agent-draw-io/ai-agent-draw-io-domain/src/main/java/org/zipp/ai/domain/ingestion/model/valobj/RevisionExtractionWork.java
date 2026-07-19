package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Exact, version-pinned source selected for one processing revision. */
public record RevisionExtractionWork(String revisionId, String detectedMediaType,
                                     long revisionFenceGeneration, long materialLifecycleGeneration,
                                     String processingFingerprint,
                                     StoredArtifact original) {
    public RevisionExtractionWork {
        revisionId = requireText(revisionId, "revisionId");
        detectedMediaType = requireText(detectedMediaType, "detectedMediaType");
        original = Objects.requireNonNull(original, "original");
        if (processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("processingFingerprint must be lowercase SHA-256");
        }
        if (revisionFenceGeneration < 0 || materialLifecycleGeneration < 0) {
            throw new IllegalArgumentException("processing generations cannot be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
