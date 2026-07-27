package org.zipp.ai.domain.operations;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBetaReleaseGateTest {

    @Test
    void passesOnlyACompleteVersionedDatasetAtEveryReleaseThreshold() {
        RagBetaReleaseGate.Decision decision = new RagBetaReleaseGate().evaluate(report(
                165, 50, passingMetrics(), 0, 0, 0, 0,
                true, true, true, true));

        assertEquals(RagBetaReleaseGate.Outcome.PASS, decision.outcome());
        assertTrue(decision.violations().isEmpty());
        assertTrue(decision.approvalIdentity().matches("rag-approval-v1:[0-9a-f]{64}"));
    }

    @Test
    void blocksMissingMetricsThinLockedSetAndAnySecurityBoundaryViolation() {
        Map<RagReleaseMetric, Double> metrics = passingMetrics();
        metrics.remove(RagReleaseMetric.CITATION_PRECISION);
        metrics.put(RagReleaseMetric.TEXT_EVIDENCE_RECALL_AT_10, 0.89);

        RagBetaReleaseGate.Decision decision = new RagBetaReleaseGate().evaluate(report(
                165, 49, metrics, 1, 1, 1, 1,
                false, false, false, false));

        assertEquals(RagBetaReleaseGate.Outcome.BLOCK, decision.outcome());
        assertTrue(decision.violations().contains("METRIC_MISSING:CITATION_PRECISION"));
        assertTrue(decision.violations().contains("METRIC_FAILED:TEXT_EVIDENCE_RECALL_AT_10"));
        assertTrue(decision.violations().contains("LOCKED_DATASET_BELOW_30_PERCENT"));
        assertTrue(decision.violations().contains("CROSS_OWNER_READ_SUCCEEDED"));
        assertTrue(decision.violations().contains("PLAIN_TEXT_REGRESSION_FAILED"));
    }

    @Test
    void zeroToleranceMetricsCannotBeRoundedIntoAReleasePass() {
        Map<RagReleaseMetric, Double> metrics = passingMetrics();
        metrics.put(RagReleaseMetric.EXPLICIT_ONLY_ESCAPE_RATE, 0.000_001);

        RagBetaReleaseGate.Decision decision = new RagBetaReleaseGate().evaluate(report(
                165, 50, metrics, 0, 0, 0, 0,
                true, true, true, true));

        assertEquals(RagBetaReleaseGate.Outcome.BLOCK, decision.outcome());
        assertTrue(decision.violations().contains("METRIC_FAILED:EXPLICIT_ONLY_ESCAPE_RATE"));
    }

    @Test
    void seedDatasetCannotApproveAReleaseEvenWithPassingMetrics() {
        RagEvaluationReport seedReport = report("rag-beta-v1-seed", 165, 50, passingMetrics(),
                0, 0, 0, 0, true, true, true, true);

        RagBetaReleaseGate.Decision decision = new RagBetaReleaseGate().evaluate(seedReport);

        assertEquals(RagBetaReleaseGate.Outcome.BLOCK, decision.outcome());
        assertTrue(decision.violations().contains("SEED_DATASET_NOT_RELEASABLE"));
    }

    @Test
    void blocksMissingOrUndersizedNoAnswerCalibration() {
        RagEvaluationReport base = report(165, 50, passingMetrics(), 0, 0, 0, 0,
                true, true, true, true);
        RagCalibrationSlice undersized = new RagCalibrationSlice("global", RagCalibrationFallback.GLOBAL,
                99, 0.01, 0.00, 0.02, 0.05, 0.03, 0.07);
        RagEvaluationReport report = new RagEvaluationReport(base.schemaVersion(), base.reportId(),
                base.datasetVersion(), base.processingProfile(), base.rankingProfile(), base.modelProfile(),
                base.deploymentProfile(), base.caseCount(), base.lockedCaseCount(), base.metrics(),
                java.util.List.of(undersized), 0, 0, 0, 0, true, true, true, true);

        RagBetaReleaseGate.Decision decision = new RagBetaReleaseGate().evaluate(report);

        assertTrue(decision.violations().contains("CALIBRATION_SAMPLE_TOO_SMALL:global"));
        assertTrue(decision.violations().contains("GLOBAL_CALIBRATION_BELOW_100_CASES"));
    }

    private RagEvaluationReport report(int cases, int lockedCases, Map<RagReleaseMetric, Double> metrics,
                                       int crossOwnerReads, int explicitEscapes, int deletedLeaseGrants,
                                       int unsafeDeliveries, boolean plainText, boolean selection,
                                       boolean atomicCommit, boolean canvasInvariant) {
        return report("rag-beta-v1", cases, lockedCases, metrics, crossOwnerReads, explicitEscapes,
                deletedLeaseGrants, unsafeDeliveries, plainText, selection, atomicCommit, canvasInvariant);
    }

    private RagEvaluationReport report(String datasetVersion, int cases, int lockedCases,
                                       Map<RagReleaseMetric, Double> metrics, int crossOwnerReads,
                                       int explicitEscapes, int deletedLeaseGrants, int unsafeDeliveries,
                                       boolean plainText, boolean selection, boolean atomicCommit,
                                       boolean canvasInvariant) {
        RagCalibrationSlice global = new RagCalibrationSlice("global", RagCalibrationFallback.GLOBAL,
                100, 0.02, 0.01, 0.03, 0.10, 0.08, 0.12);
        return new RagEvaluationReport("rag-release-report-v1", "report-2026-07-20", datasetVersion,
                "processing-v1", "ranking-v1", "model-v1", "deployment-v1", cases, lockedCases,
                metrics, java.util.List.of(global), crossOwnerReads, explicitEscapes,
                deletedLeaseGrants, unsafeDeliveries,
                plainText, selection, atomicCommit, canvasInvariant);
    }

    private Map<RagReleaseMetric, Double> passingMetrics() {
        EnumMap<RagReleaseMetric, Double> metrics = new EnumMap<>(RagReleaseMetric.class);
        for (RagReleaseMetric metric : RagReleaseMetric.values()) {
            metrics.put(metric, metric.passingBoundary());
        }
        return metrics;
    }
}
