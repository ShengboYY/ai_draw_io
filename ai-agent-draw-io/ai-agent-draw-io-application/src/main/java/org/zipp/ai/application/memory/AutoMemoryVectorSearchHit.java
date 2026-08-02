package org.zipp.ai.application.memory;

/** Ephemeral provider ranking evidence; score is never persisted as Memory authority. */
public record AutoMemoryVectorSearchHit(String vectorId, double score) {
    public AutoMemoryVectorSearchHit {
        if (vectorId == null || vectorId.isBlank() || !Double.isFinite(score)) {
            throw new IllegalArgumentException("vectorId and finite score are required");
        }
        vectorId = vectorId.trim();
    }
}
