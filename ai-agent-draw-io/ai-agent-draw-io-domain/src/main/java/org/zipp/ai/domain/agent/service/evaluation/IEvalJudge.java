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
                      EvidenceVersion evidenceVersion,
                      RenderEvidence renderEvidence) { }

    record EvidenceVersion(String model, Double temperature, String inputRendererVersion, String rubricVersion) { }

    /** Image references must be supplied through a Judge model adapter that can actually consume them. */
    record RenderEvidence(String beforeImageRef, String afterImageRef) {
        public boolean isComplete() {
            return beforeImageRef != null && !beforeImageRef.isBlank()
                    && afterImageRef != null && !afterImageRef.isBlank();
        }
    }
}
