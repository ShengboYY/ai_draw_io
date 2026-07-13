package org.zipp.ai.domain.agent.service.evaluation.controlplane;

/** Carries a stable Control Plane error code without coupling API behavior to message text. */
public final class EvalControlPlaneException extends RuntimeException {
    private final EvalControlPlaneErrorCode code;

    public EvalControlPlaneException(EvalControlPlaneErrorCode code, String message) {
        super(message);
        if (code == null) throw new IllegalArgumentException("code is required");
        this.code = code;
    }

    public EvalControlPlaneErrorCode getCode() {
        return code;
    }
}
