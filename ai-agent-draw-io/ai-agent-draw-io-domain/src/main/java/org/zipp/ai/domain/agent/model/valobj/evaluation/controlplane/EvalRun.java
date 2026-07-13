package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

import java.time.Instant;

/** Immutable run manifest plus its current execution state. */
@Value
@Builder(toBuilder = true)
public class EvalRun {
    String id;
    EvalRunMode mode;
    String datasetId;
    String datasetVersion;
    /** Derived from the immutable Dataset Version at Run creation. */
    EvaluationTarget evaluationTarget;
    String baselineRef;
    String candidateRef;
    String executionProfileHash;
    String idempotencyKey;
    int repetitions;
    int plannedEpisodes;
    double maxEstimatedCost;
    int minimumCases;
    double maximumErrorRate;
    int minimumPairedCases;
    double regressionThreshold;
    String gitSha;
    String graderManifestJson;
    String reportRef;
    EvalRunStatus status;
    String createdBy;
    Instant createdAt;
    Instant startedAt;
    Instant completedAt;
}
