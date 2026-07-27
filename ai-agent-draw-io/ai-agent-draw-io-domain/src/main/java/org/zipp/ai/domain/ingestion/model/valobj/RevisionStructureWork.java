package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Fenced whole-document input for one immutable processing revision. */
public record RevisionStructureWork(String revisionId, String versionId,
                                    long revisionFenceGeneration, long materialLifecycleGeneration,
                                    String processingFingerprint, List<RevisionCanonicalPageWork> pages) {
    public RevisionStructureWork {
        revisionId = requireText(revisionId, "revisionId");
        versionId = requireText(versionId, "versionId");
        if (revisionFenceGeneration < 0 || materialLifecycleGeneration < 0
                || processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision structure generations or profile are invalid");
        }
        pages = Objects.requireNonNull(pages, "pages").stream()
                .sorted(Comparator.comparingInt(RevisionCanonicalPageWork::pageNo)).toList();
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("revision structure requires canonical pages");
        }
        if (new HashSet<>(pages.stream().map(RevisionCanonicalPageWork::pageNo).toList()).size() != pages.size()) {
            throw new IllegalArgumentException("revision structure page numbers must be unique");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
