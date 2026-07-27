package org.zipp.ai.infrastructure.adapter.telemetry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import org.zipp.ai.domain.retrieval.port.MaterialRetrievalTelemetry;
import org.zipp.ai.domain.ingestion.port.MaterialUploadTelemetry;
import org.zipp.ai.domain.ingestion.port.MaterialIngestionTelemetry;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.operations.CapabilityState;
import org.zipp.ai.domain.operations.MaterialCapability;
import org.zipp.ai.domain.operations.MaterialCapabilityReport;
import org.zipp.ai.domain.operations.MaterialOperationalSnapshot;
import org.zipp.ai.domain.operations.MaterialCapabilityTelemetry;
import org.zipp.ai.domain.operations.CapacityLevel;
import io.micrometer.core.instrument.Counter;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Low-cardinality Micrometer projection for alerting and the operator dashboard. */
public final class MaterialOperationsMetrics implements MaterialRetrievalTelemetry,
        MaterialUploadTelemetry, MaterialIngestionTelemetry, MaterialCapabilityTelemetry {
    private final MeterRegistry registry;
    private final AtomicLong queuedJobs = new AtomicLong();
    private final AtomicLong runningJobs = new AtomicLong();
    private final AtomicLong failedJobs = new AtomicLong();
    private final AtomicLong oldestQueuedSeconds = new AtomicLong();
    private final AtomicLong activeLeases = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong stuckDeleting = new AtomicLong();
    private final AtomicLong deletionLagSeconds = new AtomicLong();
    private final AtomicLong staleVectorBatches = new AtomicLong();
    private final AtomicLong openProjectionRepairs = new AtomicLong();
    private final AtomicLong pendingOrphanDeletions = new AtomicLong();
    private final AtomicLong purgingGenerations = new AtomicLong();
    private final AtomicReference<Double> capacityPercent = new AtomicReference<>(0D);
    private final Map<MaterialCapability, Map<CapabilityState, AtomicLong>> capabilityValues;
    private final Map<CapacityLevel, AtomicLong> capacityLevelValues;

    public MaterialOperationsMetrics(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        this.registry = meters;
        register(meters, "material.ingestion.jobs", queuedJobs, "status", "queued");
        register(meters, "material.ingestion.jobs", runningJobs, "status", "running");
        register(meters, "material.ingestion.jobs", failedJobs, "status", "failed_24h");
        register(meters, "material.ingestion.queue.oldest.seconds", oldestQueuedSeconds);
        register(meters, "material.read.leases", activeLeases, "status", "active");
        register(meters, "material.read.leases", expiredLeases, "status", "expired_due");
        register(meters, "material.deletion.stuck", stuckDeleting);
        register(meters, "material.deletion.lag.seconds", deletionLagSeconds,
                "owner_type", "all", "result", "pending");
        register(meters, "material.reconciliation.backlog", staleVectorBatches, "kind", "stale_batch");
        register(meters, "material.reconciliation.backlog", openProjectionRepairs, "kind", "open_repair");
        register(meters, "material.reconciliation.backlog", pendingOrphanDeletions,
                "kind", "pending_orphan_delete");
        register(meters, "material.reconciliation.backlog", purgingGenerations,
                "kind", "purging_generation");
        Gauge.builder("material.capacity.usage.percent", capacityPercent, AtomicReference::get)
                .tag("resource", "maximum").strongReference(true).register(meters);
        capabilityValues = registerCapabilities(meters);
        capacityLevelValues = registerCapacityLevels(meters);
    }

    @Override
    public void record(String route, String sourceMode, String result,
                       java.time.Duration latency, int evidenceItems) {
        String boundedRoute = bounded(route, "unknown");
        String boundedMode = bounded(sourceMode, "unknown");
        String boundedResult = bounded(result, "failed");
        Timer.builder("material.retrieval.seconds")
                .tags("route", boundedRoute, "source_mode", boundedMode, "result", boundedResult)
                .publishPercentileHistogram().register(registry)
                .record(latency == null || latency.isNegative() ? java.time.Duration.ZERO : latency);
        DistributionSummary.builder("material.evidence.items")
                .tags("route", boundedRoute, "modality", "all")
                .register(registry).record(Math.max(0, evidenceItems));
    }

    @Override
    public void recordCandidates(String route, String lane, String stage, int candidates) {
        DistributionSummary.builder("material.retrieval.candidates")
                .tags("route", bounded(route, "unknown"), "lane", bounded(lane, "unknown"),
                        "stage", bounded(stage, "unknown"))
                .register(registry).record(Math.max(0, candidates));
    }

    @Override
    public void record(OwnerType ownerType, String result, long bytes, String mediaType) {
        String owner = ownerType == null ? "unknown" : lower(ownerType.name());
        String boundedResult = bounded(result, "failed");
        String boundedMedia = boundedMediaType(mediaType);
        Counter.builder("material.upload.total").tags("owner_type", owner, "result", boundedResult)
                .register(registry).increment();
        DistributionSummary.builder("material.upload.bytes")
                .tags("owner_type", owner, "media_type", boundedMedia)
                .register(registry).record(Math.max(0L, bytes));
    }

    @Override
    public void record(ProcessingJobStage stage, String result, java.time.Duration duration) {
        String boundedStage = stage == null ? "unknown" : lower(stage.name());
        String boundedResult = bounded(result, "failed");
        Timer.builder("material.ingestion.stage.seconds")
                .tags("stage", boundedStage, "result", boundedResult)
                .publishPercentileHistogram().register(registry)
                .record(duration == null || duration.isNegative() ? java.time.Duration.ZERO : duration);
        Counter.builder("material.ingestion.jobs.total")
                .tags("stage", boundedStage, "status", boundedResult).register(registry).increment();
        if (stage == ProcessingJobStage.VALIDATE_OWNERSHIP) {
            Counter.builder("material.scan.total").tag("result", boundedResult).register(registry).increment();
        } else if (stage == ProcessingJobStage.OCR_SELECTED_PAGES) {
            // The worker contract schedules one selected PDF page per OCR job.
            Counter.builder("material.ocr.pages.total")
                    .tags("result", boundedResult, "language_group", "eng_chi_sim")
                    .register(registry).increment();
        } else if (stage == ProcessingJobStage.EMBED_CHUNK_BATCHES) {
            Counter.builder("material.vector.operations.total")
                    .tags("operation", "embed", "result", boundedResult).register(registry).increment();
        } else if (stage == ProcessingJobStage.UPSERT_VECTOR_BATCHES
                || stage == ProcessingJobStage.REPAIR_VECTOR_BATCH) {
            Counter.builder("material.vector.operations.total")
                    .tags("operation", "upsert", "result", boundedResult).register(registry).increment();
        }
    }

    @Override
    public void publish(MaterialCapabilityReport report) {
        MaterialCapabilityReport value = Objects.requireNonNull(report, "report");
        MaterialOperationalSnapshot operations = value.operations();
        queuedJobs.set(operations.queuedJobs());
        runningJobs.set(operations.runningJobs());
        failedJobs.set(operations.failedJobsLast24Hours());
        oldestQueuedSeconds.set(operations.oldestQueuedSeconds());
        activeLeases.set(operations.activeReadLeases());
        expiredLeases.set(operations.expiredReadLeases());
        stuckDeleting.set(operations.stuckDeletingMaterials());
        deletionLagSeconds.set(operations.oldestDeletionSeconds());
        staleVectorBatches.set(operations.staleVectorBatches());
        openProjectionRepairs.set(operations.openProjectionRepairs());
        pendingOrphanDeletions.set(operations.pendingOrphanDeletions());
        purgingGenerations.set(operations.purgingGenerations());
        capacityPercent.set(value.capacityUsagePercent());
        capacityLevelValues.values().forEach(counter -> counter.set(0L));
        capacityLevelValues.get(value.capacityLevel()).set(1L);
        capabilityValues.values().forEach(states -> states.values().forEach(counter -> counter.set(0L)));
        value.capabilities().forEach((capability, state) ->
                capabilityValues.get(capability).get(state).set(1L));
    }

    private Map<MaterialCapability, Map<CapabilityState, AtomicLong>> registerCapabilities(
            MeterRegistry registry) {
        EnumMap<MaterialCapability, Map<CapabilityState, AtomicLong>> values =
                new EnumMap<>(MaterialCapability.class);
        for (MaterialCapability capability : MaterialCapability.values()) {
            EnumMap<CapabilityState, AtomicLong> states = new EnumMap<>(CapabilityState.class);
            for (CapabilityState state : CapabilityState.values()) {
                AtomicLong value = new AtomicLong();
                Gauge.builder("material.capability", value, AtomicLong::get)
                        .tag("capability", lower(capability.name()))
                        .tag("state", lower(state.name()))
                        .strongReference(true).register(registry);
                states.put(state, value);
            }
            values.put(capability, Map.copyOf(states));
        }
        return Map.copyOf(values);
    }

    private Map<CapacityLevel, AtomicLong> registerCapacityLevels(MeterRegistry registry) {
        EnumMap<CapacityLevel, AtomicLong> values = new EnumMap<>(CapacityLevel.class);
        for (CapacityLevel level : CapacityLevel.values()) {
            AtomicLong value = new AtomicLong();
            Gauge.builder("material.capacity.state", value, AtomicLong::get)
                    .tag("level", lower(level.name())).strongReference(true).register(registry);
            values.put(level, value);
        }
        return Map.copyOf(values);
    }

    private void register(MeterRegistry registry, String name, AtomicLong value, String... tags) {
        Gauge.builder(name, value, AtomicLong::get).tags(tags)
                .strongReference(true).register(registry);
    }

    private String lower(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private String bounded(String value, String fallback) {
        String normalized = value == null ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z0-9_]{1,32}") ? normalized : fallback;
    }

    private String boundedMediaType(String value) {
        if (value == null) return "unknown";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("application/pdf")) return "pdf";
        if (normalized.startsWith("image/")) return "image";
        return "other";
    }
}
