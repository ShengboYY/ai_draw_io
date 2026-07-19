package org.zipp.ai.domain.retrieval.model.valobj;

/** Owner-scoped immutable embedding cache entry; it contains no source text or provider metadata. */
public record EmbeddingCacheValue(String schemaVersion, String cacheKey, float[] values) {
    public EmbeddingCacheValue {
        if (!"embedding-cache-v1".equals(schemaVersion)
                || cacheKey == null || !cacheKey.matches("[0-9a-f]{64}")
                || values == null || values.length == 0) {
            throw new IllegalArgumentException("embedding cache value is invalid");
        }
        values = values.clone();
    }

    @Override public float[] values() { return values.clone(); }
}
