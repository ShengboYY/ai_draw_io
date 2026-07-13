package org.zipp.ai.domain.agent.service.evaluation.target;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

import java.util.List;

/** Runs route parsing, validation and compensation without allowing a drawing tool path. */
public class RouterEvalAdapter implements EvalTargetExecutionAdapter {
    @Override public EvaluationTarget target() { return EvaluationTarget.INTENT_ROUTER; }
    @Override public String runnerAdapter() { return "intent_router"; }

    @Override
    public EvalExecution executeModeB(EvalCaseDefinition evalCase, String gitSha,
                                      EvalBatchRunner.ExecutionFactory fullAgentFactory) throws Exception {
        requireNoDrawingReplay(evalCase);
        return fullAgentFactory.create(evalCase);
    }

    @Override
    public EvalExecution executeLive(EvalCaseDefinition evalCase, LiveEvalRunner.LiveExecutionFactory liveFactory) {
        return liveFactory.execute(evalCase);
    }

    private void requireNoDrawingReplay(EvalCaseDefinition evalCase) {
        EvalCaseDefinition.Replay replay = evalCase == null ? null : evalCase.getReplay();
        if (replay == null) return;
        boolean topLevelTools = replay.getToolCalls() != null && !replay.getToolCalls().isEmpty();
        boolean turnTools = safe(replay.getTurns()).stream()
                .anyMatch(turn -> turn.getToolCalls() != null && !turn.getToolCalls().isEmpty());
        if (topLevelTools || turnTools) {
            throw new IllegalArgumentException("Intent Router Case cannot contain drawing tool replay");
        }
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
