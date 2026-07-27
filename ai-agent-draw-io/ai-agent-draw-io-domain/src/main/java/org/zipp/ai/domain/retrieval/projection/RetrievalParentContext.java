package org.zipp.ai.domain.retrieval.projection;

import java.util.List;
import java.util.Objects;

/** Separately loadable parent window that preserves the Evidence identities behind its context. */
public record RetrievalParentContext(String chunkId, String parentContext, List<String> evidenceIds) {
    public RetrievalParentContext {
        if (chunkId == null || chunkId.isBlank() || parentContext == null || parentContext.isBlank()) {
            throw new IllegalArgumentException("retrieval parent context identity is invalid");
        }
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("retrieval parent context requires Evidence identities");
        }
    }
}
