package org.zipp.ai.domain.retrieval.port;

import java.util.Optional;

/** Owner-scoped vector-computation cache; misses never change authoritative projection state. */
public interface EmbeddingCachePort {
    Optional<float[]> find(String revisionId, String cacheKey, int dimension);
    void put(String revisionId, String cacheKey, float[] values);
}
