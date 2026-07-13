package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Runs the complete router, tool and response path. */
public class FullAgentEvalAdapter implements EvalTargetExecutionAdapter {
    @Override public EvaluationTarget target() { return EvaluationTarget.FULL_AGENT; }
    @Override public String runnerAdapter() { return "full_agent"; }

    @Override
    public EvalExecution executeModeB(EvalCaseDefinition evalCase, String gitSha,
                                      EvalBatchRunner.ExecutionFactory fullAgentFactory) throws Exception {
        return fullAgentFactory.create(evalCase);
    }

    @Override
    public EvalExecution executeLive(EvalCaseDefinition evalCase, LiveEvalRunner.LiveExecutionFactory liveFactory) {
        return liveFactory.execute(evalCase);
    }
}
