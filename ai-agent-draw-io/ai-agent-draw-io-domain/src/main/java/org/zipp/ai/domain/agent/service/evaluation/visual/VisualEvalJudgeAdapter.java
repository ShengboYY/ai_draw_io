package org.zipp.ai.domain.agent.service.evaluation.visual;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;

import java.util.List;

/** Adapts the visual Judge port to the common Eval Judge lifecycle. */
public class VisualEvalJudgeAdapter implements IEvalJudge {
    private final IVisualEvalJudge delegate;
    public VisualEvalJudgeAdapter(IVisualEvalJudge delegate) { this.delegate = delegate; }

    @Override public EvalJudgeResult judge(JudgeInput input) {
        if (input == null || input.initialImage() == null || input.finalImage() == null) {
            return EvalJudgeResult.builder().available(false).passed(false).judgeVersion(delegate == null ? null : delegate.version())
                    .evidence(List.of("visual_judge_unavailable:pixels_missing")).build();
        }
        return delegate.judge(new IVisualEvalJudge.VisualJudgeInput(input.caseId(), input.diagramType(), input.userTask(),
                input.initialImage(), input.finalImage(), input.deterministicIssues(), input.evaluatedAgentVersion()));
    }
}
