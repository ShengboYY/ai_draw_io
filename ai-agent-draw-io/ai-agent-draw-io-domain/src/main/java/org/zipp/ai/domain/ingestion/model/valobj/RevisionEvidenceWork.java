package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact structure, visual manifest and canonical page pins consumed by evidence construction. */
public record RevisionEvidenceWork(String revisionId, String versionId,
                                   long revisionFenceGeneration, long materialLifecycleGeneration,
                                   String processingFingerprint, StoredArtifact structureArtifact,
                                   StoredArtifact visualManifestArtifact,
                                   List<RevisionCanonicalPageWork> pages) {
    public RevisionEvidenceWork {
        if (revisionId == null || revisionId.isBlank() || versionId == null || versionId.isBlank()
                || revisionFenceGeneration < 0 || materialLifecycleGeneration < 0
                || processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision evidence work identity is invalid");
        }
        structureArtifact = Objects.requireNonNull(structureArtifact, "structureArtifact");
        visualManifestArtifact = Objects.requireNonNull(visualManifestArtifact, "visualManifestArtifact");
        pages = Objects.requireNonNull(pages, "pages").stream()
                .sorted(Comparator.comparingInt(RevisionCanonicalPageWork::pageNo)).toList();
        if (pages.isEmpty() || new HashSet<>(pages.stream().map(RevisionCanonicalPageWork::pageNo).toList())
                .size() != pages.size()) {
            throw new IllegalArgumentException("revision evidence pages must be non-empty and unique");
        }
    }
}
