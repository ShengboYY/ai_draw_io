package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** S3 payload passed from embedding to upsert; it contains vectors but never source text. */
public record VectorBatchPayload(String schemaVersion, String revisionId, String generationId,
                                 int batchNo, String batchInputFingerprint,
                                 List<VectorEmbeddingValue> embeddings) {
    public VectorBatchPayload {
        if (schemaVersion == null || schemaVersion.isBlank() || revisionId == null || revisionId.isBlank()
                || generationId == null || generationId.isBlank() || batchNo < 0
                || batchInputFingerprint == null || !batchInputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector batch payload identity is invalid");
        }
        embeddings = List.copyOf(Objects.requireNonNull(embeddings, "embeddings"));
        if (embeddings.isEmpty() || new HashSet<>(embeddings.stream()
                .map(VectorEmbeddingValue::chunkId).toList()).size() != embeddings.size()) {
            throw new IllegalArgumentException("vector batch embeddings must be non-empty and unique");
        }
    }
}
