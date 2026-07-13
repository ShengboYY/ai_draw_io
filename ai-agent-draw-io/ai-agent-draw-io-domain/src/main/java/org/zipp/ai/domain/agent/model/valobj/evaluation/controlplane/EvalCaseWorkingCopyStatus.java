package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

/** Lifecycle of a mutable case before immutable publication. */
public enum EvalCaseWorkingCopyStatus {
    DRAFT,
    VALIDATING,
    VALIDATION_FAILED,
    VALIDATED,
    DRY_RUNNING,
    DRY_RUN_FAILED,
    DRY_RUN_PASSED,
    UNDER_REVIEW,
    REJECTED,
    APPROVED,
    PUBLISHED
}
