package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;

/** Routes answer cases and diagram cases to independently versioned and calibrated Judges. */
public class RoutedEvalJudge implements IEvalJudge {
    private final IEvalJudge textJudge;
    private final IEvalJudge visualJudge;
    public RoutedEvalJudge(IEvalJudge textJudge, IEvalJudge visualJudge) { this.textJudge = textJudge; this.visualJudge = visualJudge; }

    @Override public EvalJudgeResult judge(JudgeInput input) { return selected(input).judge(input); }
    @Override public boolean isCalibrated(JudgeInput input) { IEvalJudge selected = selected(input); return selected != null && selected.isCalibrated(input); }
    private IEvalJudge selected(JudgeInput input) {
        boolean visual = input != null && input.diagramType() != null && !"none".equalsIgnoreCase(input.diagramType());
        return visual ? visualJudge : textJudge;
    }
}
