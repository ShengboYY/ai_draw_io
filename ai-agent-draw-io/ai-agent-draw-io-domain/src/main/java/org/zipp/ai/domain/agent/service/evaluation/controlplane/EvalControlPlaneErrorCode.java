package org.zipp.ai.domain.agent.service.evaluation.controlplane;

/** Stable Admin API error codes for Control Plane clients. */
public enum EvalControlPlaneErrorCode {
    NOT_FOUND,
    REVISION_CONFLICT,
    INVALID_STATE_TRANSITION,
    VALIDATION_FAILED,
    FORBIDDEN,
    ARTIFACT_UNAVAILABLE,
    INFRASTRUCTURE_ERROR
}
