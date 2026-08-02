package org.zipp.ai.infrastructure.adapter.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;

import java.util.Objects;

/** Content-free metrics for deciding whether shadow retrieval is ready to affect candidates. */
public final class AutoMemoryVectorShadowMetrics implements AutoMemoryVectorShadowTelemetry {
    private final Counter succeeded;
    private final Counter failed;
    private final DistributionSummary sqlCandidates;
    private final DistributionSummary vectorHits;

    public AutoMemoryVectorShadowMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        succeeded = registry.counter("auto_memory_vector_shadow_total", "outcome", "success");
        failed = registry.counter("auto_memory_vector_shadow_total", "outcome", "failure");
        sqlCandidates = registry.summary("auto_memory_vector_shadow_sql_candidates");
        vectorHits = registry.summary("auto_memory_vector_shadow_hits");
    }

    @Override
    public void record(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        (sample.succeeded() ? succeeded : failed).increment();
        sqlCandidates.record(sample.sqlCandidateCount());
        vectorHits.record(sample.vectorHitCount());
    }
}
