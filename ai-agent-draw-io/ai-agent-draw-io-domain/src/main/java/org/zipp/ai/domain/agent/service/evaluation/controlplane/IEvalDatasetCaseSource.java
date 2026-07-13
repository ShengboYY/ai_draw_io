package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalAdminRole;

import java.util.List;

public interface IEvalDatasetCaseSource {
    List<EvalCaseDefinition> loadPublished(String datasetId, String version, EvalAdminRole role);
}
