package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationActivationPolicy;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationBackfillStatus;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationShadowReport;
import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;
import org.zipp.ai.domain.retrieval.service.IndexGenerationActivationGate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexGenerationActivationGateTest {

    private static final String POLICY_FINGERPRINT = "a".repeat(64);

    @Test
    void acceptsOnlyCompleteShadowGenerationWithPassingPinnedReport() {
        var gate = new IndexGenerationActivationGate();
        var status = new GenerationBackfillStatus("ig_new", IndexGenerationState.SHADOW,
                "ig_old", 3, 3, 3, 12, 12, 3);
        var policy = new GenerationActivationPolicy(POLICY_FINGERPRINT, 100,
                -0.01, 0.0, 1.20);
        var report = report(0, 0.91, 0.90, 0.82, 0.80, 110, 100);

        assertDoesNotThrow(() -> gate.verify(status, report, policy));
    }

    @Test
    void rejectsIncompleteBackfillAuthorizationDriftAndMetricRegression() {
        var gate = new IndexGenerationActivationGate();
        var policy = new GenerationActivationPolicy(POLICY_FINGERPRINT, 100,
                -0.01, 0.0, 1.20);

        assertThrows(IllegalStateException.class, () -> gate.verify(
                new GenerationBackfillStatus("ig_new", IndexGenerationState.SHADOW,
                        "ig_old", 3, 3, 2, 12, 11, 2),
                report(0, 0.91, 0.90, 0.82, 0.80, 110, 100), policy));
        assertThrows(IllegalStateException.class, () -> gate.verify(
                complete(), report(1, 0.91, 0.90, 0.82, 0.80, 110, 100), policy));
        assertThrows(IllegalStateException.class, () -> gate.verify(
                complete(), report(0, 0.80, 0.90, 0.70, 0.80, 130, 100), policy));
        assertThrows(IllegalStateException.class, () -> gate.verify(
                new GenerationBackfillStatus("ig_new", IndexGenerationState.SHADOW,
                        null, 3, 3, 3, 12, 12, 3),
                report(0, 0.91, 0.90, 0.82, 0.80, 110, 100), policy));
    }

    private GenerationBackfillStatus complete() {
        return new GenerationBackfillStatus("ig_new", IndexGenerationState.SHADOW,
                "ig_old", 3, 3, 3, 12, 12, 3);
    }

    private GenerationShadowReport report(int authorizationMismatches,
                                          double recall, double baselineRecall,
                                          double ndcg, double baselineNdcg,
                                          long p95, long baselineP95) {
        return new GenerationShadowReport("generation-shadow-report-v1", "report_1",
                "ig_new", "ig_old", 3, POLICY_FINGERPRINT, 120, authorizationMismatches,
                recall, baselineRecall, ndcg, baselineNdcg, p95, baselineP95,
                Instant.parse("2026-07-20T00:00:00Z"));
    }
}
