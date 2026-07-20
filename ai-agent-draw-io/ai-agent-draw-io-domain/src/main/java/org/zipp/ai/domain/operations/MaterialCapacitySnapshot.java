package org.zipp.ai.domain.operations;

/** Low-cardinality provider and monthly-budget utilization snapshot. */
public record MaterialCapacitySnapshot(double embeddingPercent, double indexedPagesPercent,
                                       double vectorReadPercent, double vectorWritePercent,
                                       boolean dependenciesAvailable) {
    public MaterialCapacitySnapshot {
        validatePercent(embeddingPercent);
        validatePercent(indexedPagesPercent);
        validatePercent(vectorReadPercent);
        validatePercent(vectorWritePercent);
    }

    public double maximumUsagePercent() {
        return Math.max(Math.max(embeddingPercent, indexedPagesPercent),
                Math.max(vectorReadPercent, vectorWritePercent));
    }

    private static void validatePercent(double value) {
        if (!Double.isFinite(value) || value < 0D || value > 100D) {
            throw new IllegalArgumentException("capacity percentage must be between 0 and 100");
        }
    }
}
