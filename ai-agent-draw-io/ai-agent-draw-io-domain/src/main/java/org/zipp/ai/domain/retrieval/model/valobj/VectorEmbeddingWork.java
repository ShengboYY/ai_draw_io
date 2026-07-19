package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.util.Objects;

/** One deterministic passage batch authorized for embedding under a persisted generation profile. */
public record VectorEmbeddingWork(RevisionProjectionContext context, VectorGenerationProfile profile,
                                  int batchNo, String workKey, String batchInputFingerprint) {
    public VectorEmbeddingWork {
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
        if (batchNo < 0 || workKey == null || workKey.isBlank()
                || batchInputFingerprint == null || !batchInputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector embedding work identity is invalid");
        }
    }
}
