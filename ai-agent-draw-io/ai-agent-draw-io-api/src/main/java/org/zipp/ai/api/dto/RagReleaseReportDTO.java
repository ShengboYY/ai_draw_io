package org.zipp.ai.api.dto;

import java.util.List;
import java.util.Map;

public record RagReleaseReportDTO(String schemaVersion, String reportId, String datasetVersion,
                                  String processingProfile, String rankingProfile,
                                  String modelProfile, String deploymentProfile,
                                  int caseCount, int lockedCaseCount,
                                  Map<String, Double> metrics,
                                  List<CalibrationSliceDTO> calibrationSlices,
                                  int crossOwnerReadSuccessCount,
                                  int explicitOnlyEscapeCount,
                                  int deletedOrExpiredLeaseGrantCount,
                                  int preScanDeliveryCount,
                                  boolean plainTextRegressionPassed,
                                  boolean selectionHighlightSmokePassed,
                                  boolean atomicCommitSmokePassed,
                                  boolean answerCanvasInvariantPassed) {
    public record CalibrationSliceDTO(String sliceKey, String fallback, int sampleCount,
                                      double falseSupportedRate,
                                      double falseSupportedConfidenceLow,
                                      double falseSupportedConfidenceHigh,
                                      double falseAbstentionRate,
                                      double falseAbstentionConfidenceLow,
                                      double falseAbstentionConfidenceHigh) { }
}
