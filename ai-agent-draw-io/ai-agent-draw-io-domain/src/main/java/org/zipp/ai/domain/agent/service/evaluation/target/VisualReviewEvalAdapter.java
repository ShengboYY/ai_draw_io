package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Runs the production reviewer directly; deterministic replay is intentionally unsupported. */
public class VisualReviewEvalAdapter implements EvalTargetExecutionAdapter {
    @Override public EvaluationTarget target() { return EvaluationTarget.VISUAL_REVIEW; }
    @Override public String runnerAdapter() { return "visual_review"; }

    @Override
    public EvalExecution executeModeB(EvalCaseDefinition evalCase, String gitSha,
                                      EvalBatchRunner.ExecutionFactory ignored) {
        throw new IllegalArgumentException("Visual Review Cases require live Mode C execution");
    }

    @Override
    public EvalExecution executeLive(EvalCaseDefinition evalCase,
                                     LiveEvalRunner.LiveExecutionFactory liveFactory) {
        return liveFactory.execute(evalCase);
    }
}
