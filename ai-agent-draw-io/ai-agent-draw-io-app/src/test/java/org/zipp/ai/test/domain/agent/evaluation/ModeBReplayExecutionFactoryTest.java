package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModeBReplayExecutionFactoryTest {

    @Test
    public void shouldApplyEveryRecordedRepairToolCallInOrder() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/core-v1/edit-add-worker.yaml")) {
            EvalCaseDefinition evalCase = new EvalCaseLoader().load(input);

            EvalExecution execution = new ModeBReplayExecutionFactory("phase1-test-sha").create(evalCase);

            assertEquals(3, execution.getTrace().getToolCalls().size());
            assertTrue(execution.getFinalCanvasXml().contains("x=\"380\""));
            assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
        }
    }

    @Test
    public void shouldCarryCanvasStateAcrossClarificationAndEditTurns() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/core-v1/clarify-then-edit.yaml")) {
            EvalCaseDefinition evalCase = new EvalCaseLoader().load(input);

            EvalExecution execution = new ModeBReplayExecutionFactory("phase4-test-sha").create(evalCase);
            EvalHarnessResult result = new DefaultEvalHarness().evaluate(execution);

            assertEquals(2, execution.getTurns().size());
            assertEquals(execution.getTurns().get(0).getAfterCanvasXml(), execution.getTurns().get(1).getBeforeCanvasXml());
            assertTrue(result.isPassed());
            assertTrue(result.getGraders().stream().anyMatch(grader -> "multi_turn_state".equals(grader.getGraderName())));
            assertTrue(result.getGraders().stream().anyMatch(grader -> "preservation".equals(grader.getGraderName())));
            assertTrue(result.getGraders().stream().anyMatch(grader -> "graph_assertion".equals(grader.getGraderName())));
        }
    }
}
