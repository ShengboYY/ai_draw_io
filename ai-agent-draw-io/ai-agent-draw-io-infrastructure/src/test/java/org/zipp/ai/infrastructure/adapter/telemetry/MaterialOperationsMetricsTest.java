package org.zipp.ai.infrastructure.adapter.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import org.zipp.ai.domain.operations.MaterialCapabilityReport;
import org.zipp.ai.domain.operations.MaterialCapabilityService;
import org.zipp.ai.domain.operations.MaterialCapacityBreaker;
import org.zipp.ai.domain.operations.MaterialCapacitySnapshot;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialOperationalSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialOperationsMetricsTest {

    @Test
    void publishesOnlyBoundedOperationalAndCapabilityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MaterialOperationsMetrics metrics = new MaterialOperationsMetrics(registry);
        MaterialCapabilityReport report = new MaterialCapabilityService(
                () -> new MaterialOperationalSnapshot(Instant.EPOCH, 7, 2, 1,
                        125, 3, 1, 2, 400, 900, 1, 2, 3, 4),
                new MaterialCapacityBreaker(() ->
                        new MaterialCapacitySnapshot(72, 30, 20, 10, true)))
                .assess(MaterialFeatureSet.allEnabled(), true);

        metrics.publish(report);
        metrics.record("TEXT", "AUTO", "READY", java.time.Duration.ofMillis(25), 4);

        assertEquals(7D, registry.get("material.ingestion.jobs")
                .tag("status", "queued").gauge().value());
        assertEquals(125D, registry.get("material.ingestion.queue.oldest.seconds").gauge().value());
        assertEquals(72D, registry.get("material.capacity.usage.percent")
                .tag("resource", "maximum").gauge().value());
        assertEquals(1D, registry.get("material.capacity.state")
                .tag("level", "warning").gauge().value());
        assertEquals(1D, registry.get("material.capability")
                .tag("capability", "plain_text_drawing").tag("state", "available").gauge().value());
        assertEquals(1L, registry.get("material.retrieval.seconds")
                .tag("route", "text").tag("source_mode", "auto").tag("result", "ready")
                .timer().count());
        assertEquals(3D, registry.get("material.reconciliation.backlog")
                .tag("kind", "pending_orphan_delete").gauge().value());
    }
}
