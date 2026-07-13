package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

/** Dataset versions become immutable at PUBLISHED. */
public enum EvalDatasetVersionStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    RETIRED
}
