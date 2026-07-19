package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationActivationPolicy;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationBackfillStatus;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationShadowReport;
import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;
import org.zipp.ai.domain.retrieval.port.IndexGenerationCompatibilityPort;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.service.IndexGenerationActivationGate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Application coordinator for a single immutable generation profile owned by this Worker. */
public final class IndexGenerationCompatibilityCoordinator {
    private final IndexGenerationCompatibilityPort compatibility;
    private final IndexGenerationActivationGate activationGate;
    private final VectorGenerationProfile profile;
    private final int batchSize;
    private final Clock clock;

    public IndexGenerationCompatibilityCoordinator(IndexGenerationCompatibilityPort compatibility,
                                                   IndexGenerationActivationGate activationGate,
                                                   VectorGenerationProfile profile, int batchSize,
                                                   Clock clock) {
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
        this.profile = Objects.requireNonNull(profile, "profile");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${worker.generation-sync-delay-ms:5000}")
    public void synchronize() {
        Instant now = clock.instant();
        GenerationBackfillStatus status = compatibility.synchronize(profile, batchSize, now);
        if (status.state() == IndexGenerationState.BUILDING && status.complete()) {
            compatibility.beginShadow(status, now);
        }
    }

    public boolean recordShadowReport(GenerationShadowReport report) {
        GenerationShadowReport source = Objects.requireNonNull(report, "report");
        if (!profile.generationId().equals(source.generationId())) {
            throw new IllegalArgumentException("shadow report belongs to another Worker generation");
        }
        return compatibility.recordShadowReport(source);
    }

    public boolean activate(String reportId, GenerationActivationPolicy policy, Duration rollbackWindow) {
        String pinnedReportId = requireText(reportId, "reportId");
        Duration retention = Objects.requireNonNull(rollbackWindow, "rollbackWindow");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("rollbackWindow must be positive");
        }
        GenerationBackfillStatus status = compatibility.findStatus(profile.generationId())
                .orElseThrow(() -> new IllegalStateException("generation does not exist"));
        GenerationShadowReport report = compatibility.findShadowReport(profile.generationId(), pinnedReportId)
                .orElseThrow(() -> new IllegalStateException("shadow report does not exist"));
        activationGate.verify(status, report, policy);
        Instant activatedAt = clock.instant();
        return compatibility.activate(status, report, activatedAt, activatedAt.plus(retention));
    }

    public boolean rollback() {
        return compatibility.rollback(profile.generationId(), clock.instant());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
