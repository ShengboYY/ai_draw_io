package org.zipp.ai.application.memory;

import java.util.Objects;

/** Provider-neutral vector paired with its server-owned Memory projection identity. */
public record AutoMemoryVector(AutoMemoryVectorDocument document, float[] values) {
    public AutoMemoryVector {
        document = Objects.requireNonNull(document, "document");
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }
}
