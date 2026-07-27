package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Canonical page content and its exact immutable artifact pin used to derive evidence. */
public record EvidenceSourcePage(String pageId, CanonicalPage page, StoredArtifact canonicalArtifact) {
    public EvidenceSourcePage {
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("evidence source pageId is required");
        }
        page = Objects.requireNonNull(page, "page");
        canonicalArtifact = Objects.requireNonNull(canonicalArtifact, "canonicalArtifact");
    }
}
