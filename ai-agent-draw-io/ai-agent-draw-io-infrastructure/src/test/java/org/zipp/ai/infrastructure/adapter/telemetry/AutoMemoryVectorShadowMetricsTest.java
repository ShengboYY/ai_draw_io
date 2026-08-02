package org.zipp.ai.infrastructure.adapter.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoMemoryVectorShadowMetricsTest {
    @Test
    void recordsOnlyCountsAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AutoMemoryVectorShadowMetrics metrics = new AutoMemoryVectorShadowMetrics(registry);

        metrics.record(new AutoMemoryVectorShadowTelemetry.Sample(
                true, 12, 2));
        metrics.record(new AutoMemoryVectorShadowTelemetry.Sample(
                false, 8, 0));

        assertEquals(1.0d, registry.get("auto_memory_vector_shadow_total")
                .tag("outcome", "success").counter().count());
        assertEquals(1.0d, registry.get("auto_memory_vector_shadow_total")
                .tag("outcome", "failure").counter().count());
        assertEquals(20.0d, registry.get("auto_memory_vector_shadow_sql_candidates")
                .summary().totalAmount());
        assertEquals(2.0d, registry.get("auto_memory_vector_shadow_hits")
                .summary().totalAmount());
    }
}
