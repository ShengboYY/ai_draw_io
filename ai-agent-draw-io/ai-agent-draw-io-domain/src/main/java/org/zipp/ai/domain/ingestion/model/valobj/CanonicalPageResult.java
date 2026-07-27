package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record CanonicalPageResult(int pageNo, StoredArtifact canonicalPage) {
    public CanonicalPageResult {
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be positive");
        }
        canonicalPage = Objects.requireNonNull(canonicalPage, "canonicalPage");
    }
}
