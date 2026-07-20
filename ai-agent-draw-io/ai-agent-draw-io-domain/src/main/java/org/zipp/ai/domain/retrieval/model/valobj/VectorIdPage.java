package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.List;

/** One bounded provider page used for exact-ID orphan reconciliation. */
public record VectorIdPage(List<String> vectorIds, String nextToken) {
    public VectorIdPage {
        vectorIds = List.copyOf(vectorIds);
        if (vectorIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("vector IDs must be non-blank");
        }
        nextToken = nextToken == null || nextToken.isBlank() ? null : nextToken.trim();
    }
}
