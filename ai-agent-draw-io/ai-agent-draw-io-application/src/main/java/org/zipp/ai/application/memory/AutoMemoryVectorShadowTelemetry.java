package org.zipp.ai.application.memory;

/** Records only shadow counts; raw text, Memory content, and vector identities are excluded. */
@FunctionalInterface
public interface AutoMemoryVectorShadowTelemetry {
    AutoMemoryVectorShadowTelemetry NOOP = sample -> {
    };

    void record(Sample sample);

    record Sample(
            boolean succeeded,
            int sqlCandidateCount,
            int vectorHitCount,
            int hydratedCandidateCount,
            int overlapCount
    ) {
        public Sample {
            if (sqlCandidateCount < 0 || vectorHitCount < 0
                    || hydratedCandidateCount < 0 || overlapCount < 0
                    || hydratedCandidateCount > vectorHitCount
                    || overlapCount > Math.min(sqlCandidateCount, hydratedCandidateCount)) {
                throw new IllegalArgumentException("shadow counts are inconsistent");
            }
        }
    }
}
