package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;

import java.time.Instant;

/** Immutable metadata for a published synthetic case artifact. */
@Value
@Builder(toBuilder = true)
public class EvalCaseVersion {
    String caseId;
    String caseVersion;
    String contentHash;
    String artifactRef;
    EvaluationTarget evaluationTarget;
    EvaluationTargetMigrationStatus targetMigrationStatus;
    String approvedBy;
    Instant publishedAt;
    Instant retiredAt;
}
