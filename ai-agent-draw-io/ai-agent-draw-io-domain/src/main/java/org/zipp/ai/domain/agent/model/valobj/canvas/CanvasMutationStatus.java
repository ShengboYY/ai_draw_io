package org.zipp.ai.domain.agent.model.valobj.canvas;

public enum CanvasMutationStatus {
    ACCEPTED,
    ACCEPTED_WITH_NOTES,
    REJECTED_INVALID_CANDIDATE,
    REJECTED_SCOPE_VIOLATION,
    REJECTED_REGRESSION,
    STALE_VERSION,
    NO_SAFE_CANDIDATE
}
