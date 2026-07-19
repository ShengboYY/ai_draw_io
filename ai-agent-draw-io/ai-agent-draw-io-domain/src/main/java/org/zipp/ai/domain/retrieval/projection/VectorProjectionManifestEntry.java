package org.zipp.ai.domain.retrieval.projection;

/** Auditable identity of one indexed chunk projection; it deliberately contains no source text. */
public record VectorProjectionManifestEntry(String chunkId, String vectorId, String projectionFingerprint) {
    public VectorProjectionManifestEntry {
        if (chunkId == null || chunkId.isBlank() || vectorId == null || vectorId.isBlank()
                || projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector projection manifest entry is invalid");
        }
    }
}
