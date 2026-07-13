package org.zipp.ai.domain.agent.service.evaluation.visual;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;

import java.util.List;

/** Visual-only Judge port. It is deliberately separate from production anomaly discovery. */
public interface IVisualEvalJudge {
    EvalJudgeResult judge(VisualJudgeInput input);
    String version();

    record VisualJudgeInput(String caseId, String diagramType, String userTask,
                            IDiagramImageRenderer.RenderedDiagram before,
                            IDiagramImageRenderer.RenderedDiagram after,
                            List<String> analyzerEvidence,
                            IEvalJudge.EvaluatedAgentVersion evaluatedAgentVersion) { }
}
