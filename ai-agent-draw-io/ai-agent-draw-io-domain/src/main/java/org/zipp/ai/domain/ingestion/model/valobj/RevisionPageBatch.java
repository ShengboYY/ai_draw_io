package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record RevisionPageBatch(String revisionId, long revisionFenceGeneration,
                                long materialLifecycleGeneration,
                                String processingFingerprint,
                                List<RevisionPageWork> pages) {
    public RevisionPageBatch {
        if (revisionId == null || revisionId.isBlank() || revisionFenceGeneration < 0
                || materialLifecycleGeneration < 0) {
            throw new IllegalArgumentException("revision batch identity is invalid");
        }
        revisionId = revisionId.trim();
        if (processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("processingFingerprint must be lowercase SHA-256");
        }
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("revision batch requires pages");
        }
    }
}
