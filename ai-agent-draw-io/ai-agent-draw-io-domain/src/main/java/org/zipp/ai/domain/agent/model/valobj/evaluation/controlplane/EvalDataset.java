package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Stable dataset identity; individual versions carry immutable membership snapshots. */
@Value
@Builder
public class EvalDataset {
    String id;
    String name;
    EvalDatasetClass datasetClass;
    String ownerUserId;
    Instant createdAt;
}
