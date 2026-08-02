package org.zipp.ai.infrastructure.adapter.vector;

/** Opaque Pinecone query match with its transient provider score. */
public record PineconeVectorMatch(String id, double score) {
    public PineconeVectorMatch {
        if (id == null || id.isBlank() || !Double.isFinite(score)) {
            throw new IllegalArgumentException("Pinecone match id and finite score are required");
        }
        id = id.trim();
    }
}
