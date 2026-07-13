package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Executes exactly one Evaluation target while preserving the shared Episode contract. */
public interface EvalTargetExecutionAdapter {
    EvaluationTarget target();
    String runnerAdapter();
    EvalExecution executeModeB(EvalCaseDefinition evalCase, String gitSha,
                               EvalBatchRunner.ExecutionFactory fullAgentFactory) throws Exception;
    EvalExecution executeLive(EvalCaseDefinition evalCase,
                              LiveEvalRunner.LiveExecutionFactory liveFactory);
}
