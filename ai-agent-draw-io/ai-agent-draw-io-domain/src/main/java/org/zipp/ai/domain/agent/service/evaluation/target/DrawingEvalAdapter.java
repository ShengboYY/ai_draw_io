package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.ModeBDrawingReplayExecutionFactory;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Runs the independent drawing path; derived Full Agent Episodes are intentionally out of R4 scope. */
public class DrawingEvalAdapter implements EvalTargetExecutionAdapter {
    @Override public EvaluationTarget target() { return EvaluationTarget.DRAWING_QUALITY; }
    @Override public String runnerAdapter() { return "drawing"; }

    @Override
    public EvalExecution executeModeB(EvalCaseDefinition evalCase, String gitSha,
                                      EvalBatchRunner.ExecutionFactory ignored) throws Exception {
        return new ModeBDrawingReplayExecutionFactory(gitSha).create(evalCase);
    }

    @Override
    public EvalExecution executeLive(EvalCaseDefinition evalCase, LiveEvalRunner.LiveExecutionFactory liveFactory) {
        return liveFactory.execute(evalCase);
    }
}
