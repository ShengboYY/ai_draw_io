package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Durable operational record for one asynchronous semantic anomaly scan. */
@Data
@Builder
public class SemanticMinerRun {
    private String id;
    private SemanticMinerRunStatus status;
    private SemanticSamplingPolicy samplingPolicy;
    private Integer requestedLimit;
    private Integer sampledCount;
    private Integer analyzedCount;
    private Integer candidateCount;
    private Integer errorCount;
    private Double estimatedCostUsd;
    private String modelVersion;
    private String sanitizerVersion;
    private String availabilityReason;
    private String createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
}
