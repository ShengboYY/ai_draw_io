package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;

import java.util.List;

/** Versioned semantic/experience Judge boundary; implementations must use a calibrated rubric. */
public interface IEvalJudge {
    EvalJudgeResult judge(JudgeInput input);

    /** Raw model-backed Judges are never release-eligible until a calibrated wrapper says otherwise. */
    default boolean isCalibrated() { return false; }

    record JudgeInput(String caseId, String diagramType, String userTask,
                      DrawioGraphNormalizer.Graph initialGraph,
                      DrawioGraphNormalizer.Graph finalGraph,
                      String responseText,
                      List<String> deterministicIssues,
                      List<String> toolTraceSummary,
                      EvaluatedAgentVersion evaluatedAgentVersion) { }

    /** Version of the evaluated Agent evidence, kept separate from the Judge provider's own model version. */
    record EvaluatedAgentVersion(String model, Double temperature, String inputProjectionVersion, String rubricVersion) { }
}
