package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

/** States for restricted trace-to-eval intake records; published cases live outside this lifecycle. */
public enum EvalCandidateStatus {
    DETECTED,
    TRIAGED,
    DRAFT_READY,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    EXPIRED,
    PURGED
}
