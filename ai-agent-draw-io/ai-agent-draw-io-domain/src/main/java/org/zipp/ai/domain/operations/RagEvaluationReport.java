package org.zipp.ai.domain.operations;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-free release report produced by the locked RAG evaluation dataset. */
public record RagEvaluationReport(String schemaVersion, String reportId, String datasetVersion,
                                  String processingProfile, String rankingProfile,
                                  String modelProfile, String deploymentProfile,
                                  int caseCount, int lockedCaseCount,
                                  Map<RagReleaseMetric, Double> metrics,
                                  List<RagCalibrationSlice> calibrationSlices,
                                  int crossOwnerReadSuccessCount,
                                  int explicitOnlyEscapeCount,
                                  int deletedOrExpiredLeaseGrantCount,
                                  int preScanDeliveryCount,
                                  boolean plainTextRegressionPassed,
                                  boolean selectionHighlightSmokePassed,
                                  boolean atomicCommitSmokePassed,
                                  boolean answerCanvasInvariantPassed) {
    public RagEvaluationReport {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        reportId = requireText(reportId, "reportId");
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        processingProfile = requireText(processingProfile, "processingProfile");
        rankingProfile = requireText(rankingProfile, "rankingProfile");
        modelProfile = requireText(modelProfile, "modelProfile");
        deploymentProfile = requireText(deploymentProfile, "deploymentProfile");
        if (caseCount < 0 || lockedCaseCount < 0 || lockedCaseCount > caseCount
                || crossOwnerReadSuccessCount < 0 || explicitOnlyEscapeCount < 0
                || deletedOrExpiredLeaseGrantCount < 0 || preScanDeliveryCount < 0) {
            throw new IllegalArgumentException("RAG release report counts are invalid");
        }
        EnumMap<RagReleaseMetric, Double> copy = new EnumMap<>(RagReleaseMetric.class);
        if (metrics != null) {
            metrics.forEach((metric, value) -> {
                if (metric == null || value == null || !Double.isFinite(value)
                        || value < 0D || value > 1D) {
                    throw new IllegalArgumentException("RAG release metrics must be finite ratios");
                }
                copy.put(metric, value);
            });
        }
        metrics = Map.copyOf(copy);
        calibrationSlices = List.copyOf(calibrationSlices == null ? List.of() : calibrationSlices);
    }

    /** Stable pin over the exact report, profiles, gates and aggregate evaluation evidence. */
    public String approvalIdentity() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, schemaVersion);
        append(canonical, reportId);
        append(canonical, datasetVersion);
        append(canonical, processingProfile);
        append(canonical, rankingProfile);
        append(canonical, modelProfile);
        append(canonical, deploymentProfile);
        append(canonical, Integer.toString(caseCount));
        append(canonical, Integer.toString(lockedCaseCount));
        for (RagReleaseMetric metric : RagReleaseMetric.values()) {
            if (metrics.containsKey(metric)) {
                append(canonical, metric.name());
                append(canonical, Double.toHexString(metrics.get(metric)));
            }
        }
        calibrationSlices.stream().sorted(java.util.Comparator.comparing(RagCalibrationSlice::sliceKey))
                .forEach(slice -> {
                    append(canonical, slice.sliceKey());
                    append(canonical, slice.fallback().name());
                    append(canonical, Integer.toString(slice.sampleCount()));
                    append(canonical, Double.toHexString(slice.falseSupportedRate()));
                    append(canonical, Double.toHexString(slice.falseSupportedConfidenceLow()));
                    append(canonical, Double.toHexString(slice.falseSupportedConfidenceHigh()));
                    append(canonical, Double.toHexString(slice.falseAbstentionRate()));
                    append(canonical, Double.toHexString(slice.falseAbstentionConfidenceLow()));
                    append(canonical, Double.toHexString(slice.falseAbstentionConfidenceHigh()));
                });
        append(canonical, Integer.toString(crossOwnerReadSuccessCount));
        append(canonical, Integer.toString(explicitOnlyEscapeCount));
        append(canonical, Integer.toString(deletedOrExpiredLeaseGrantCount));
        append(canonical, Integer.toString(preScanDeliveryCount));
        append(canonical, Boolean.toString(plainTextRegressionPassed));
        append(canonical, Boolean.toString(selectionHighlightSmokePassed));
        append(canonical, Boolean.toString(atomicCommitSmokePassed));
        append(canonical, Boolean.toString(answerCanvasInvariantPassed));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "rag-approval-v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > 160 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be a bounded opaque identifier");
        }
        return normalized;
    }
}
