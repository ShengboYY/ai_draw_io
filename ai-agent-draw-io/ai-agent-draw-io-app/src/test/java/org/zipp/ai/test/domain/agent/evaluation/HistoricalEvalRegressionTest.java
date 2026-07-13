package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;

import java.io.InputStream;

import static org.junit.Assert.*;

public class HistoricalEvalRegressionTest {

    @Test
    public void sanitizedFalseSuccessSymptomMustFailWhileCorrectedReplayPasses() throws Exception {
        EvalCaseDefinition evalCase;
        try (InputStream input = getClass().getResourceAsStream(
                "/evals/core-v1/regression-edit-success-with-unchanged-canvas.yaml")) {
            evalCase = new EvalCaseLoader().load(input);
        }
        assertEquals("c49f0a68", evalCase.getRegression().getExpectedBaseline().getGitSha());
        assertEquals("FAIL", evalCase.getRegression().getExpectedBaseline().getOutcome());

        EvalExecution candidate = new ModeBReplayExecutionFactory("current-artifact-fix").create(evalCase);
        EvalHarnessResult candidateResult = new DefaultEvalHarness().evaluate(candidate);
        assertEquals(EvalHarnessResult.Status.PASS, candidateResult.getStatus());

        // Reconstruct the sanitized historical symptom; ProductionLiveEvalAdapterTest separately drives the fixed seam.
        candidate.setGitSha(evalCase.getRegression().getExpectedBaseline().getGitSha());
        candidate.setFinalCanvasXml(candidate.getInitialCanvasXml());
        candidate.getTrace().setAfterCanvasHash(candidate.getTrace().getBeforeCanvasHash());
        candidate.getTrace().setTaskOutcome(EvalTrace.TaskOutcome.FULFILLED);
        EvalHarnessResult baselineResult = new DefaultEvalHarness().evaluate(candidate);

        assertEquals(EvalHarnessResult.Status.FAIL, baselineResult.getStatus());
        assertTrue(baselineResult.getGraders().stream().flatMap(grader -> grader.getEvidence().stream())
                .anyMatch(evidence -> evidence.contains("Canvas hash did not change") || evidence.contains("Gateway")));
    }
}
