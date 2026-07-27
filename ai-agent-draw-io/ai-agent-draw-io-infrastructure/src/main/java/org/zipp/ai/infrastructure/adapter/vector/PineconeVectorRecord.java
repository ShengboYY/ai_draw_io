package org.zipp.ai.infrastructure.adapter.vector;

import java.util.LinkedHashMap;
import java.util.Map;

/** Vector projection whose metadata is validated again by the Pinecone adapter. */
public record PineconeVectorRecord(String id, float[] values, Map<String, Object> metadata) {

    public PineconeVectorRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Vector id is required");
        }
        values = values == null ? new float[0] : values.clone();
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.copyOf(metadata);
    }
}
