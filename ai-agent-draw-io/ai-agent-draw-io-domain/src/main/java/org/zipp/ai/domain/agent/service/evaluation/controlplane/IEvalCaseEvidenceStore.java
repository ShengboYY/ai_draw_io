package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseEvidence;

import java.util.List;

public interface IEvalCaseEvidenceStore {
    void insert(EvalCaseEvidence evidence);
    List<EvalCaseEvidence> list(String workingCopyId);
}
