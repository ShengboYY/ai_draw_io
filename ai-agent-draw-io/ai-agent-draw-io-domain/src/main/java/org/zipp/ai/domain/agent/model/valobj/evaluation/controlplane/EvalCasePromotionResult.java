package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

/** Idempotent response shared by the new and compatibility Promote endpoints. */
public record EvalCasePromotionResult(
        Status status,
        String candidateId,
        String workingCopyId,
        String caseId,
        String caseVersion) {

    public enum Status {
        CREATED,
        EXISTING_DRAFT,
        ALREADY_PUBLISHED
    }
}
