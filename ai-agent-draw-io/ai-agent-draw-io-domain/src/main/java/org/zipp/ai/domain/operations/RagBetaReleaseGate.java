package org.zipp.ai.domain.operations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fail-closed Beta gate; it never infers a missing metric from nearby evaluation data. */
public final class RagBetaReleaseGate {
    private static final int MINIMUM_CASES = 165;

    public Decision evaluate(RagEvaluationReport report) {
        RagEvaluationReport evidence = Objects.requireNonNull(report, "report");
        List<String> violations = new ArrayList<>();
        if (!"rag-release-report-v1".equals(evidence.schemaVersion())) {
            violations.add("REPORT_SCHEMA_UNSUPPORTED");
        }
        // Seed fixtures validate the case contract only and can never approve a deployment.
        if (evidence.datasetVersion().endsWith("-seed")) {
            violations.add("SEED_DATASET_NOT_RELEASABLE");
        }
        if (evidence.caseCount() < MINIMUM_CASES) violations.add("DATASET_BELOW_165_CASES");
        if (evidence.caseCount() == 0
                || evidence.lockedCaseCount() * 10L < evidence.caseCount() * 3L) {
            violations.add("LOCKED_DATASET_BELOW_30_PERCENT");
        }
        for (RagReleaseMetric metric : RagReleaseMetric.values()) {
            Double value = evidence.metrics().get(metric);
            if (value == null) violations.add("METRIC_MISSING:" + metric.name());
            else if (!metric.passes(value)) violations.add("METRIC_FAILED:" + metric.name());
        }
        if (evidence.calibrationSlices().isEmpty()) {
            violations.add("NO_ANSWER_CALIBRATION_MISSING");
        }
        boolean globalCalibration = false;
        for (RagCalibrationSlice slice : evidence.calibrationSlices()) {
            if (!slice.hasEnoughSamples()) violations.add("CALIBRATION_SAMPLE_TOO_SMALL:" + slice.sliceKey());
            if (slice.falseSupportedRate() > 0.02D) {
                violations.add("CALIBRATION_FALSE_SUPPORTED_FAILED:" + slice.sliceKey());
            }
            if (slice.falseAbstentionRate() > 0.10D) {
                violations.add("CALIBRATION_FALSE_ABSTENTION_FAILED:" + slice.sliceKey());
            }
            globalCalibration |= slice.fallback() == RagCalibrationFallback.GLOBAL
                    && slice.sampleCount() >= RagCalibrationFallback.GLOBAL.minimumSamples();
        }
        if (!globalCalibration) violations.add("GLOBAL_CALIBRATION_BELOW_100_CASES");
        addCountViolation(violations, evidence.crossOwnerReadSuccessCount(), "CROSS_OWNER_READ_SUCCEEDED");
        addCountViolation(violations, evidence.explicitOnlyEscapeCount(), "EXPLICIT_ONLY_BOUNDARY_ESCAPED");
        addCountViolation(violations, evidence.deletedOrExpiredLeaseGrantCount(), "INVALID_LIFECYCLE_LEASE_GRANTED");
        addCountViolation(violations, evidence.preScanDeliveryCount(), "CONTENT_DELIVERED_BEFORE_SCAN");
        if (!evidence.plainTextRegressionPassed()) violations.add("PLAIN_TEXT_REGRESSION_FAILED");
        if (!evidence.selectionHighlightSmokePassed()) violations.add("SELECTION_HIGHLIGHT_SMOKE_FAILED");
        if (!evidence.atomicCommitSmokePassed()) violations.add("ATOMIC_COMMIT_SMOKE_FAILED");
        if (!evidence.answerCanvasInvariantPassed()) violations.add("ANSWER_CANVAS_INVARIANT_FAILED");
        Outcome outcome = violations.isEmpty() ? Outcome.PASS : Outcome.BLOCK;
        return new Decision(outcome, List.copyOf(violations),
                outcome == Outcome.PASS ? evidence.approvalIdentity() : "");
    }

    private void addCountViolation(List<String> violations, int count, String code) {
        if (count != 0) violations.add(code);
    }

    public enum Outcome { PASS, BLOCK }
    public record Decision(Outcome outcome, List<String> violations, String approvalIdentity) { }
}
