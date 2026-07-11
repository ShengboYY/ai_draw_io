package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.service.evaluation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.Assert.*;

public class EvalReleaseGateServiceTest {
    @Test
    public void deterministicHardFailureMustBlockImmediately() {
        EvalHarnessResult failed = EvalHarnessResult.builder().caseId("critical-case").status(EvalHarnessResult.Status.FAIL).passed(false).build();

        EvalReleaseGateService.Decision decision = new EvalReleaseGateService().evaluate(input(failed, true, 10));

        assertEquals(EvalReleaseGateService.Outcome.BLOCK, decision.outcome());
    }

    @Test
    public void missingCalibrationOrSequesteredCasesMustProduceNoDecision() {
        EvalHarnessResult passed = EvalHarnessResult.builder().caseId("case").status(EvalHarnessResult.Status.PASS).passed(true).build();

        EvalReleaseGateService.Decision decision = new EvalReleaseGateService().evaluate(input(passed, false, 0));

        assertEquals(EvalReleaseGateService.Outcome.NO_DECISION, decision.outcome());
    }

    @Test
    public void infrastructureErrorMustNotBeMisreportedAsAgentRegression() {
        EvalHarnessResult error = EvalHarnessResult.builder().caseId("case").status(EvalHarnessResult.Status.ERROR).passed(false).build();

        EvalReleaseGateService.Decision decision = new EvalReleaseGateService().evaluate(input(error, true, 10));

        assertEquals(EvalReleaseGateService.Outcome.NO_DECISION, decision.outcome());
    }

    @Test
    public void allApprovedEvidenceCanPassAndWriteReports() throws Exception {
        EvalHarnessResult passed = EvalHarnessResult.builder().caseId("case").status(EvalHarnessResult.Status.PASS).passed(true).build();
        EvalReleaseGateService.Decision decision = new EvalReleaseGateService().evaluate(input(passed, true, 10));
        Path report = Files.createTempDirectory("release-gate-");
        new EvalReleaseReportWriter().write(decision, report);

        assertEquals(EvalReleaseGateService.Outcome.PASS, decision.outcome());
        assertTrue(Files.exists(report.resolve("release-gate.json")));
    }

    @Test
    public void sequesteredCasesMustLoadOnlyFromOutsideTheRepository() throws Exception {
        Path repository = Files.createTempDirectory("product-repo-");
        Path external = Files.createTempDirectory("sequestered-");
        Path source = Path.of(getClass().getResource("/evals/core-v1/edit-api-gateway.yaml").toURI());
        Files.copy(source, external.resolve("release.yaml"), StandardCopyOption.REPLACE_EXISTING);

        assertEquals(1, new SequesteredEvalCaseLoader().load(external, repository).size());
        try {
            new SequesteredEvalCaseLoader().load(repository, repository);
            fail("repository-contained sequestered root must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside"));
        }
    }

    private EvalReleaseGateService.Input input(EvalHarnessResult deterministic, boolean calibrated, int sequestered) {
        EvalStatisticalReport stats = EvalStatisticalReport.builder().decision(EvalStatisticalReport.Decision.READY).build();
        EvalStatisticalReport.Comparison comparison = EvalStatisticalReport.Comparison.builder()
                .decision(EvalStatisticalReport.Decision.READY).blocked(false).build();
        JudgeCalibrationService.Report calibration = JudgeCalibrationService.Report.builder().approved(calibrated).judgeVersion("judge-v1").build();
        return new EvalReleaseGateService.Input(List.of(new EvalReleaseGateService.CaseResult("case", "high", deterministic)),
                stats, comparison, calibration, true, sequestered, 5);
    }
}
