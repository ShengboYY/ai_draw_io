package org.zipp.ai.domain.retrieval.model.valobj;

/** One generated vector bound to the chunk, vector ID, and projection fingerprint it represents. */
public record VectorEmbeddingValue(String chunkId, String vectorId, String projectionFingerprint,
                                   float[] values) {
    public VectorEmbeddingValue {
        if (chunkId == null || chunkId.isBlank() || vectorId == null || vectorId.isBlank()
                || projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")
                || values == null || values.length == 0) {
            throw new IllegalArgumentException("vector embedding value is invalid");
        }
        values = values.clone();
    }

    @Override public float[] values() { return values.clone(); }
}
