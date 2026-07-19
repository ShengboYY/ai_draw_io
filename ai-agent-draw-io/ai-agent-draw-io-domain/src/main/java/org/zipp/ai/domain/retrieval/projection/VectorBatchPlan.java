package org.zipp.ai.domain.retrieval.projection;

import java.util.List;
import java.util.Objects;

/** Durable unit of embedding and upsert work. */
public record VectorBatchPlan(int batchNo, String workKey, String inputFingerprint,
                              List<VectorProjectionTarget> projections) {
    public VectorBatchPlan {
        if (batchNo < 0 || workKey == null || workKey.isBlank()
                || inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector batch identity is invalid");
        }
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        if (projections.isEmpty()) {
            throw new IllegalArgumentException("vector batch must not be empty");
        }
    }
}
