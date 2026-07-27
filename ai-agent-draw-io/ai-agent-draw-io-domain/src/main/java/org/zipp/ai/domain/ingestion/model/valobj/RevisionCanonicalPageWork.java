package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Exact page and artifact pins consumed by whole-document structure analysis. */
public record RevisionCanonicalPageWork(String pageId, int pageNo, StoredArtifact pageImage,
                                        StoredArtifact canonicalPage) {
    public RevisionCanonicalPageWork {
        if (pageId == null || pageId.isBlank() || pageNo < 1) {
            throw new IllegalArgumentException("revision canonical page identity is invalid");
        }
        pageId = pageId.trim();
        pageImage = Objects.requireNonNull(pageImage, "pageImage");
        canonicalPage = Objects.requireNonNull(canonicalPage, "canonicalPage");
    }
}
