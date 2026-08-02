package org.zipp.ai.application.memory;

/** Records only shadow counts; raw text, Memory content, and vector identities are excluded. */
@FunctionalInterface
public interface AutoMemoryVectorShadowTelemetry {
    AutoMemoryVectorShadowTelemetry NOOP = sample -> {
    };

    void record(Sample sample);

    record Sample(boolean succeeded, int sqlCandidateCount, int vectorHitCount) {
        public Sample {
            if (sqlCandidateCount < 0 || vectorHitCount < 0) {
                throw new IllegalArgumentException("shadow counts must not be negative");
            }
        }
    }
}
