package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

/** Execution state; release outcome is deliberately stored separately. */
public enum EvalRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    INFRA_ERROR
}
