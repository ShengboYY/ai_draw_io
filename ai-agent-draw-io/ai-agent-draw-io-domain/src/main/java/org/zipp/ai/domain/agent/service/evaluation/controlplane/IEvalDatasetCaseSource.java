package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;

import java.util.List;

public interface IEvalDatasetCaseSource {
    List<EvalCaseDefinition> loadPublished(String datasetId, String version, EvalAdminRole role);

    /** Derives the immutable Dataset target from its published Case artifacts. */
    default EvaluationTarget target(String datasetId, String version, EvalAdminRole role) {
        EvaluationTarget target = null;
        for (EvalCaseDefinition definition : loadPublished(datasetId, version, role)) {
            if (definition.getEvaluationTarget() == null) return null;
            if (target == null) target = definition.getEvaluationTarget();
            else if (target != definition.getEvaluationTarget()) {
                throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_MISMATCH,
                        "published Dataset contains mixed evaluation targets");
            }
        }
        return target;
    }
}
