package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import java.util.Objects;

/** Stable UI grouping over the existing Candidate lifecycle; it is not a second write state. */
public enum TraceFindingStatusGroup {
    NEW,
    TRIAGED,
    DRAFTING,
    IN_REVIEW,
    APPROVED,
    DISMISSED,
    PROMOTED;

    public static TraceFindingStatusGroup from(EvalCandidateStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case DETECTED -> NEW;
            case TRIAGED -> TRIAGED;
            case DRAFT_READY, NEEDS_MANUAL_RECONSTRUCTION -> DRAFTING;
            case UNDER_REVIEW -> IN_REVIEW;
            case APPROVED -> APPROVED;
            case REJECTED, EXPIRED, PURGED -> DISMISSED;
            case PUBLISHED -> PROMOTED;
        };
    }
}
