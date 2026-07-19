package org.zipp.ai.domain.retrieval.model.valobj;

import java.util.Map;

public record VectorProjection(String retrievalChunkId, String indexGenerationId, String vectorId,
                               float[] values, Map<String, Object> metadata) {

    public VectorProjection {
        retrievalChunkId = requireText(retrievalChunkId, "retrievalChunkId");
        indexGenerationId = requireText(indexGenerationId, "indexGenerationId");
        vectorId = requireText(vectorId, "vectorId");
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values are required");
        }
        values = values.clone();
        metadata = Map.copyOf(metadata);
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
