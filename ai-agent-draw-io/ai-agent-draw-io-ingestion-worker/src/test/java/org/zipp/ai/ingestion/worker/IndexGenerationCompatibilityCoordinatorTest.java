package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.IndexGenerationCompatibilityPort;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.service.IndexGenerationActivationGate;

import java.time.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexGenerationCompatibilityCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void synchronizationMovesOnlyACompleteBuildingGenerationIntoShadow() {
        FakePort port = new FakePort();
        port.status = status(IndexGenerationState.BUILDING, 2, 2);
        var coordinator = coordinator(port);

        coordinator.synchronize();

        assertTrue(port.shadowStarted);
        assertEquals(profile().generationId(), port.synchronizedGenerationId);

        port.shadowStarted = false;
        port.status = status(IndexGenerationState.BUILDING, 2, 1);
        coordinator.synchronize();
        assertFalse(port.shadowStarted);
    }

    @Test
    void activationPinsThePassingReportAndRollbackDeadline() {
        FakePort port = new FakePort();
        port.status = status(IndexGenerationState.SHADOW, 2, 2);
        port.report = report();
        var coordinator = coordinator(port);
        var policy = new GenerationActivationPolicy("a".repeat(64), 100, -0.01, 0, 1.2);

        assertTrue(coordinator.activate("report_1", policy, Duration.ofDays(7)));

        assertEquals(NOW.plus(Duration.ofDays(7)), port.rollbackUntil);
        assertEquals("report_1", port.activatedReportId);
        assertTrue(coordinator.rollback());
        assertEquals(profile().generationId(), port.rolledBackGenerationId);
    }

    private IndexGenerationCompatibilityCoordinator coordinator(FakePort port) {
        return new IndexGenerationCompatibilityCoordinator(port, new IndexGenerationActivationGate(),
                profile(), 100, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GenerationBackfillStatus status(IndexGenerationState state, int required, int ready) {
        return new GenerationBackfillStatus(profile().generationId(), state, "ig_old",
                required, required, ready, 5, ready == required ? 5 : 4, ready);
    }

    private GenerationShadowReport report() {
        return new GenerationShadowReport("generation-shadow-report-v1", "report_1",
                profile().generationId(), "ig_old", 2, "a".repeat(64), 120, 0,
                0.91, 0.90, 0.82, 0.80, 110, 100, NOW.minusSeconds(60));
    }

    private VectorGenerationProfile profile() {
        return new VectorGenerationProfile("rag-v2", "prod", "e5-v2", "b".repeat(64),
                4, "cosine", "vector-v2", "tokenizer-v1");
    }

    private static final class FakePort implements IndexGenerationCompatibilityPort {
        private GenerationBackfillStatus status;
        private GenerationShadowReport report;
        private boolean shadowStarted;
        private String synchronizedGenerationId;
        private String activatedReportId;
        private Instant rollbackUntil;
        private String rolledBackGenerationId;

        @Override public GenerationBackfillStatus synchronize(VectorGenerationProfile profile,
                                                               int batchSize, Instant now) {
            synchronizedGenerationId = profile.generationId();
            return status;
        }

        @Override public boolean beginShadow(GenerationBackfillStatus expected, Instant now) {
            shadowStarted = true;
            return true;
        }

        @Override public boolean recordShadowReport(GenerationShadowReport report) {
            this.report = report;
            return true;
        }

        @Override public Optional<GenerationBackfillStatus> findStatus(String generationId) {
            return Optional.ofNullable(status);
        }

        @Override public Optional<GenerationShadowReport> findShadowReport(String generationId,
                                                                           String reportId) {
            return Optional.ofNullable(report);
        }

        @Override public boolean activate(GenerationBackfillStatus expected,
                                          GenerationShadowReport report,
                                          Instant activatedAt, Instant rollbackUntil) {
            activatedReportId = report.reportId();
            this.rollbackUntil = rollbackUntil;
            return true;
        }

        @Override public boolean rollback(String activeGenerationId, Instant rolledBackAt) {
            rolledBackGenerationId = activeGenerationId;
            return true;
        }
    }
}
