package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

import java.time.Instant;
import java.util.List;

/** Immutable dataset snapshot once status reaches PUBLISHED. */
@Value
@Builder(toBuilder = true)
public class EvalDatasetVersion {
    String datasetId;
    String version;
    EvalDatasetClass datasetClass;
    /** Snapshot of the member-derived Dataset target. */
    EvaluationTarget evaluationTarget;
    EvalDatasetVersionStatus status;
    String contentHash;
    Long revision;
    @Singular List<EvalDatasetMember> members;
    String publishedBy;
    Instant publishedAt;
}
