package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;

import java.util.ArrayList;
import java.util.List;

/** Replays only recorded canvas tool cells; no Router or full Agent is invoked. */
public class ModeBDrawingReplayExecutionFactory implements EvalBatchRunner.ExecutionFactory {
    private final String gitSha;
    private final ReplayCanvasToolExecutor tools = new ReplayCanvasToolExecutor();

    public ModeBDrawingReplayExecutionFactory(String gitSha) {
        this.gitSha = gitSha;
    }

    @Override
    public EvalExecution create(EvalCaseDefinition evalCase) {
        EvalCaseDefinition.Replay replay = evalCase == null ? null : evalCase.getReplay();
        if (replay == null) throw new IllegalArgumentException("replay is required for Mode B");
        String initialXml = require(replay.getInitialCanvasXml(), "replay.initialCanvasXml");
        if (replay.getTurns() != null && !replay.getTurns().isEmpty()) {
            throw new IllegalArgumentException("Drawing-only replay currently supports one turn");
        }
        String finalXml = initialXml;
        List<EvalTrace.ToolCall> calls = new ArrayList<>();
        for (EvalCaseDefinition.ReplayToolCall call : safe(replay.getToolCalls())) {
            finalXml = tools.execute(call, finalXml);
            calls.add(EvalTrace.ToolCall.builder().name(call.getName()).status(EvalTrace.RunStatus.SUCCESS).build());
        }
        EvalTrace trace = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(replay.getTaskOutcome() == null ? EvalTrace.TaskOutcome.UNKNOWN : replay.getTaskOutcome())
                .toolCalls(calls).beforeCanvasHash(hash(initialXml)).afterCanvasHash(hash(finalXml)).build();
        return EvalExecution.builder().evalCase(evalCase).trace(trace).initialCanvasXml(initialXml)
                .finalCanvasXml(finalXml).gitSha(gitSha).build();
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private String hash(String value) { return Integer.toHexString(value.hashCode()); }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
