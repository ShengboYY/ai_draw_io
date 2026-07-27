package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class GenerationShadowReportPO {
    private String reportId;
    private String schemaVersion;
    private String indexGenerationId;
    private String baselineGenerationId;
    private long targetGeneration;
    private String policyFingerprint;
    private int sampleCount;
    private int authorizationMismatchCount;
    private double candidateRecallAt40;
    private double baselineRecallAt40;
    private double candidateNdcgAt16;
    private double baselineNdcgAt16;
    private long candidateP95LatencyMs;
    private long baselineP95LatencyMs;
    private Instant evaluatedAt;
}
