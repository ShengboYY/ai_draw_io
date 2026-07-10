package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class EvalBatchRunnerTest {
    @Test
    public void shouldRunTheDiskCaseThroughModeBAndWriteReports() throws Exception {
        Path report = Files.createTempDirectory("eval-report-");
        Path cases = Path.of(getClass().getResource("/evals/core-v1").toURI());
        List<EvalHarnessResult> results = new EvalBatchRunner().run(
                cases, new ModeBReplayExecutionFactory(), report);
        assertTrue(results.stream().anyMatch(result -> "edit-api-gateway-001".equals(result.getCaseId()) && result.isPassed()));
        assertTrue(results.stream().anyMatch(result -> "create-login-flow-001".equals(result.getCaseId()) && result.isPassed()));
        assertTrue(Files.exists(report.resolve("eval-report.json")));
        assertTrue(Files.exists(report.resolve("eval-report.md")));
        String markdown = Files.readString(report.resolve("eval-report.md"));
        assertTrue(markdown.contains("grader xml_integrity@xml-integrity-v1: PASS"));
        assertTrue(markdown.contains("latency:"));
    }

    @Test
    public void shouldRecordOneBadCaseAsErrorAndContinueTheBatch() throws Exception {
        Path cases = Files.createTempDirectory("eval-cases-");
        Path valid = Path.of(getClass().getResource("/evals/core-v1/edit-api-gateway.yaml").toURI());
        Files.copy(valid, cases.resolve("valid.yaml"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(cases.resolve("invalid.yaml"), "caseId: invalid\ndatasetVersion: core-v1\nprivacy:\n  classification: private\n");

        List<EvalHarnessResult> results = new EvalBatchRunner().run(
                cases, new ModeBReplayExecutionFactory(), Files.createTempDirectory("eval-report-"));

        assertTrue(results.stream().anyMatch(result -> result.getStatus() == EvalHarnessResult.Status.PASS));
        assertTrue(results.stream().anyMatch(result -> result.getStatus() == EvalHarnessResult.Status.ERROR));
    }
}
