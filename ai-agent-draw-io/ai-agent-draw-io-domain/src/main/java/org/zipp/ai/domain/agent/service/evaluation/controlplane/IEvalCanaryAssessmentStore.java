package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCanaryAssessment;

import java.util.List;

public interface IEvalCanaryAssessmentStore {
    void insert(EvalCanaryAssessment assessment);
    List<EvalCanaryAssessment> list(String evalRunId, int limit);
}
