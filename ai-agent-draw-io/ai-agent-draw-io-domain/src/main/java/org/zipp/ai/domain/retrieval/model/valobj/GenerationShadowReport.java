package org.zipp.ai.domain.retrieval.model.valobj;

import java.time.Instant;
import java.util.Objects;

/** Content-free shadow metrics pinned to one candidate/baseline generation pair and policy. */
public record GenerationShadowReport(String schemaVersion, String reportId,
                                     String generationId, String baselineGenerationId,
                                     long targetGeneration, String policyFingerprint, int sampleCount,
                                     int authorizationMismatchCount,
                                     double candidateRecallAt40, double baselineRecallAt40,
                                     double candidateNdcgAt16, double baselineNdcgAt16,
                                     long candidateP95LatencyMs, long baselineP95LatencyMs,
                                     Instant createdAt) {
    public GenerationShadowReport {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        reportId = requireText(reportId, "reportId");
        generationId = requireText(generationId, "generationId");
        baselineGenerationId = requireText(baselineGenerationId, "baselineGenerationId");
        if (generationId.equals(baselineGenerationId)
                || targetGeneration < 1
                || policyFingerprint == null || !policyFingerprint.matches("[0-9a-f]{64}")
                || sampleCount < 1 || authorizationMismatchCount < 0
                || !ratio(candidateRecallAt40) || !ratio(baselineRecallAt40)
                || !ratio(candidateNdcgAt16) || !ratio(baselineNdcgAt16)
                || candidateP95LatencyMs < 0 || baselineP95LatencyMs < 1) {
            throw new IllegalArgumentException("generation shadow report is invalid");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
