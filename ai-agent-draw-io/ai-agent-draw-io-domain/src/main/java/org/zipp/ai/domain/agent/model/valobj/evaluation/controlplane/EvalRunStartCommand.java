package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** Immutable request used to create a reproducible Mode B run manifest. */
@Value
@Builder
public class EvalRunStartCommand {
    String idempotencyKey;
    String datasetId;
    String datasetVersion;
    int repetitions;
    String gitSha;
    String executionProfileHash;
    String createdBy;
}
