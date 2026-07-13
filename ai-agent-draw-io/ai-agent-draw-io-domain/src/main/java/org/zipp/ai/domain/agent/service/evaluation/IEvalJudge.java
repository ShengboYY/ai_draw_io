package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;

import java.util.List;

/** Versioned semantic/experience Judge boundary; implementations must use a calibrated rubric. */
public interface IEvalJudge {
    EvalJudgeResult judge(JudgeInput input);

    /** Raw model-backed Judges are never release-eligible until a calibrated wrapper says otherwise. */
    default boolean isCalibrated() { return false; }
    default boolean isCalibrated(JudgeInput input) { return isCalibrated(); }

    record JudgeInput(String caseId, String diagramType, String userTask,
                      DrawioGraphNormalizer.Graph initialGraph,
                      DrawioGraphNormalizer.Graph finalGraph,
                      String responseText,
                      List<String> deterministicIssues,
                      List<String> toolTraceSummary,
                      EvaluatedAgentVersion evaluatedAgentVersion,
                      IDiagramImageRenderer.RenderedDiagram initialImage,
                      IDiagramImageRenderer.RenderedDiagram finalImage) {
        public JudgeInput(String caseId, String diagramType, String userTask,
                          DrawioGraphNormalizer.Graph initialGraph, DrawioGraphNormalizer.Graph finalGraph,
                          String responseText, List<String> deterministicIssues, List<String> toolTraceSummary,
                          EvaluatedAgentVersion evaluatedAgentVersion) {
            this(caseId, diagramType, userTask, initialGraph, finalGraph, responseText, deterministicIssues,
                    toolTraceSummary, evaluatedAgentVersion, null, null);
        }
    }

    /** Version of the evaluated Agent evidence, kept separate from the Judge provider's own model version. */
    record EvaluatedAgentVersion(String model, Double temperature, String inputProjectionVersion, String rubricVersion) { }
}
