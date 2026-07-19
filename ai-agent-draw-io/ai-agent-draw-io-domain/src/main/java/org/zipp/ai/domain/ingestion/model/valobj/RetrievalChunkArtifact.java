package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Exact immutable artifacts written for one retrieval chunk. */
public record RetrievalChunkArtifact(String chunkId, StoredArtifact retrievalTextArtifact,
                                     StoredArtifact parentContextArtifact) {
    public RetrievalChunkArtifact {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId is required");
        }
        retrievalTextArtifact = Objects.requireNonNull(retrievalTextArtifact, "retrievalTextArtifact");
    }
}
