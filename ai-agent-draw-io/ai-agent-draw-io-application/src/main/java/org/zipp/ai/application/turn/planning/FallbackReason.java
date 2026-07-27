package org.zipp.ai.application.turn.planning;

/** Closed reasons that may enter a planner-signed Optional fallback branch. */
public enum FallbackReason {
    SOURCE_UNAVAILABLE,
    SOURCE_NO_MATCH,
    SNAPSHOT_DEPENDENCY_UNAVAILABLE,
    EVIDENCE_INSUFFICIENT,
    RETRIEVAL_DEPENDENCY_FAILURE,
    GROUNDED_CITATION_REJECTED_BEFORE_COMMIT
}
