package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact structure and page-image pins consumed by local visual preparation. */
public record RevisionVisualWork(String revisionId, String versionId,
                                 long revisionFenceGeneration, long materialLifecycleGeneration,
                                 String processingFingerprint, StoredArtifact structureArtifact,
                                 List<RevisionCanonicalPageWork> pages) {
    public RevisionVisualWork {
        if (revisionId == null || revisionId.isBlank() || versionId == null || versionId.isBlank()
                || revisionFenceGeneration < 0 || materialLifecycleGeneration < 0
                || processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision visual work identity is invalid");
        }
        structureArtifact = Objects.requireNonNull(structureArtifact, "structureArtifact");
        pages = Objects.requireNonNull(pages, "pages").stream()
                .sorted(Comparator.comparingInt(RevisionCanonicalPageWork::pageNo)).toList();
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("revision visual work requires pages");
        }
        if (new HashSet<>(pages.stream().map(RevisionCanonicalPageWork::pageNo).toList()).size() != pages.size()) {
            throw new IllegalArgumentException("revision visual page numbers must be unique");
        }
    }
}
