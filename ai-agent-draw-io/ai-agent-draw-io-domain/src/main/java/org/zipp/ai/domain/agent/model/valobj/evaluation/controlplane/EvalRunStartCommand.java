package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** Immutable request used to create a reproducible evaluation run manifest. */
@Value
@Builder
public class EvalRunStartCommand {
    EvalRunMode mode;
    String idempotencyKey;
    String datasetId;
    String datasetVersion;
    String baselineRef;
    String candidateRef;
    String profileId;
    String profileVersion;
    int repetitions;
    String gitSha;
    String executionProfileHash;
    double maxEstimatedCost;
    int minimumCases;
    double maximumErrorRate;
    int minimumPairedCases;
    double regressionThreshold;
    String createdBy;
}
