package org.zipp.ai.domain.agent.model.valobj.canvas;

public enum CanvasMutationRejectionReason {
    INVALID_CANDIDATE,
    SCOPE_VIOLATION,
    QUALITY_REGRESSION,
    VERSION_MISMATCH,
    CONTENT_HASH_MISMATCH,
    NO_SAFE_CANDIDATE
}
