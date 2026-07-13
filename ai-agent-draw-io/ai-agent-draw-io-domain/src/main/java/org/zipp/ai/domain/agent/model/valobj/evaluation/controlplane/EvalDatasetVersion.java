package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/** Immutable dataset snapshot once status reaches PUBLISHED. */
@Value
@Builder
public class EvalDatasetVersion {
    String datasetId;
    String version;
    EvalDatasetClass datasetClass;
    EvalDatasetVersionStatus status;
    String contentHash;
    @Singular List<EvalDatasetMember> members;
    String publishedBy;
    Instant publishedAt;
}
