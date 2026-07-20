package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;
import java.util.Set;

/** Exact, version-pinned source selected for one processing revision. */
public record RevisionExtractionWork(String revisionId, String detectedMediaType,
                                     long revisionFenceGeneration, long materialLifecycleGeneration,
                                     String processingFingerprint,
                                     Set<Integer> excludedPages,
                                     StoredArtifact original) {
    public RevisionExtractionWork(String revisionId, String detectedMediaType,
                                  long revisionFenceGeneration, long materialLifecycleGeneration,
                                  String processingFingerprint, StoredArtifact original) {
        this(revisionId, detectedMediaType, revisionFenceGeneration, materialLifecycleGeneration,
                processingFingerprint, Set.of(), original);
    }

    public RevisionExtractionWork {
        revisionId = requireText(revisionId, "revisionId");
        detectedMediaType = requireText(detectedMediaType, "detectedMediaType");
        original = Objects.requireNonNull(original, "original");
        excludedPages = Set.copyOf(Objects.requireNonNull(excludedPages, "excludedPages"));
        if (excludedPages.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("excludedPages must contain positive page numbers");
        }
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
