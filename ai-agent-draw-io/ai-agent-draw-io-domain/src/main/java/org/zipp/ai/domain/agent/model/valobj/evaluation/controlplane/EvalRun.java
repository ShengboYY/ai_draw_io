package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Immutable run manifest plus its current execution state. */
@Value
@Builder
public class EvalRun {
    String id;
    EvalRunMode mode;
    String datasetId;
    String datasetVersion;
    String baselineRef;
    String candidateRef;
    String executionProfileHash;
    EvalRunStatus status;
    String createdBy;
    Instant createdAt;
    Instant startedAt;
    Instant completedAt;
}
