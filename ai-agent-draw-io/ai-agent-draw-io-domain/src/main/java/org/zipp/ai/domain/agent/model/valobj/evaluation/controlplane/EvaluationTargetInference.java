package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;

/** Deterministic migration decision for a legacy Case without an explicit Target. */
public record EvaluationTargetInference(
        EvaluationTarget target,
        EvaluationTargetMigrationStatus status,
        String reason) {
}
