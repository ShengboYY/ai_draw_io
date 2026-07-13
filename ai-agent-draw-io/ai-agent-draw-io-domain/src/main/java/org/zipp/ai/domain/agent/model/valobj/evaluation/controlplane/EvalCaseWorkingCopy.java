package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;

import java.time.Instant;

/**
 * Mutable administration envelope around the one canonical EvalCaseDefinition.
 * Production trace identifiers are allowed only as a short-lived candidate reference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseWorkingCopy {
    private String id;
    private String caseId;
    private String caseVersion;
    private EvalCaseSourceType sourceType;
    private String candidateId;
    private EvalCaseWorkingCopyStatus status;
    private String ownerUserId;
    private Long revision;
    private EvalCaseDefinition definition;
    private EvaluationTarget evaluationTarget;
    private EvaluationTargetMigrationStatus targetMigrationStatus;
    private Instant createdAt;
    private Instant updatedAt;
}
