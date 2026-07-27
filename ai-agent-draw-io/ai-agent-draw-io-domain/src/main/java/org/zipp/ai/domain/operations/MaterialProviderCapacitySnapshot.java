package org.zipp.ai.domain.operations;

import java.time.Instant;
import java.util.Objects;

/** Timestamped, content-free capacity sample supplied by the provider billing exporter. */
public record MaterialProviderCapacitySnapshot(Instant capturedAt, long sequence, double embeddingPercent,
                                               double vectorReadPercent, double vectorWritePercent,
                                               boolean dependenciesAvailable) {
    public MaterialProviderCapacitySnapshot {
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        if (sequence < 0) throw new IllegalArgumentException("provider capacity sequence cannot be negative");
        validate(embeddingPercent);
        validate(vectorReadPercent);
        validate(vectorWritePercent);
    }

    private static void validate(double value) {
        if (!Double.isFinite(value) || value < 0D || value > 100D) {
            throw new IllegalArgumentException("provider capacity percentage must be between 0 and 100");
        }
    }
}
