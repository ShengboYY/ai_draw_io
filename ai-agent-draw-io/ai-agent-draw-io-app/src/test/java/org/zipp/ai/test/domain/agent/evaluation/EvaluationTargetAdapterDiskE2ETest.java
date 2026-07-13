package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;
import org.zipp.ai.domain.agent.service.evaluation.target.EvalTargetExecutionAdapter;
import org.zipp.ai.domain.agent.service.evaluation.target.EvalTargetExecutionAdapters;

import java.io.InputStream;

import static org.junit.Assert.*;

public class EvaluationTargetAdapterDiskE2ETest {
    private final EvalTargetExecutionAdapters adapters = new EvalTargetExecutionAdapters();

    @Test
    public void everyTargetRunsItsOwnDiskCaseEndToEnd() throws Exception {
        EvalExecution full = execute("full-agent.yaml", EvaluationTarget.FULL_AGENT, "full_agent");
        EvalExecution router = execute("router.yaml", EvaluationTarget.INTENT_ROUTER, "intent_router");
        EvalExecution drawing = execute("drawing.yaml", EvaluationTarget.DRAWING_QUALITY, "drawing");

        assertTrue(new DefaultEvalHarness().evaluate(full).isPassed());
        assertTrue(new DefaultEvalHarness().evaluate(router).isPassed());
        assertTrue(new DefaultEvalHarness().evaluate(drawing).isPassed());
        assertTrue(router.getTrace().getToolCalls().isEmpty());
        assertEquals(router.getInitialCanvasXml(), router.getFinalCanvasXml());
        assertNull(drawing.getTrace().getRouting());
        assertEquals("modify_diagram", drawing.getTrace().getToolCalls().get(0).getName());
    }

    @Test
    public void routerAdapterRejectsAccidentalDrawingReplay() throws Exception {
        EvalCaseDefinition evalCase = load("router.yaml");
        evalCase.getReplay().getToolCalls().add(EvalCaseDefinition.ReplayToolCall.builder()
                .name("modify_diagram").mode("append").cells("<mxCell id='2'/>").build());
        EvalTargetExecutionAdapter adapter = adapters.require(EvaluationTarget.INTENT_ROUTER, "intent_router");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> adapter.executeModeB(evalCase, "r4-test", new ModeBReplayExecutionFactory("r4-test")));

        assertTrue(failure.getMessage().contains("cannot contain drawing tool replay"));
    }

    private EvalExecution execute(String fixture, EvaluationTarget target, String adapterName) throws Exception {
        EvalCaseDefinition evalCase = load(fixture);
        assertEquals(target, evalCase.getEvaluationTarget());
        return adapters.require(target, adapterName).executeModeB(evalCase, "r4-test",
                new ModeBReplayExecutionFactory("r4-test"));
    }

    private EvalCaseDefinition load(String fixture) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/targets-r4/" + fixture)) {
            assertNotNull(input);
            return new EvalCaseLoader().load(input);
        }
    }
}
