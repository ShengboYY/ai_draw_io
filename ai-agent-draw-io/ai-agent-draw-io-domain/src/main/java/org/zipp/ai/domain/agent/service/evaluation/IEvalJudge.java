package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;

/** Versioned semantic/experience Judge boundary; implementations must use a calibrated rubric. */
public interface IEvalJudge {
    EvalJudgeResult judge(JudgeInput input);

    record JudgeInput(String caseId, String diagramType, String userTask,
                      DrawioGraphNormalizer.Graph normalizedGraph, String responseText) { }
}
