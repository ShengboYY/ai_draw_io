package org.zipp.ai.domain.agent.service.evaluation.controlplane;

/** Target types added to the existing admin audit log. */
public final class EvalControlPlaneAuditTypes {
    public static final String CASE_WORKING_COPY = "EVAL_CASE_WORKING_COPY";
    public static final String CASE_VERSION = "EVAL_CASE_VERSION";
    public static final String DATASET_VERSION = "EVAL_DATASET_VERSION";
    public static final String EVAL_RUN = "EVAL_RUN";
    public static final String RELEASE_GATE = "EVAL_RELEASE_GATE";

    private EvalControlPlaneAuditTypes() {
    }
}
