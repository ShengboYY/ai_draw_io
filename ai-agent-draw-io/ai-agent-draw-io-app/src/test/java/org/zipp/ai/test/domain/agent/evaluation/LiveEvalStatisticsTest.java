package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.service.evaluation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LiveEvalStatisticsTest {

    @Test
    public void shouldRetryOnlyInfrastructureErrorsAndRunRequiredJudge() {
        EvalCaseDefinition evalCase = caseDefinition("case-1", true);
        AtomicInteger calls = new AtomicInteger();
        IEvalJudge judge = input -> EvalJudgeResult.builder().available(true).passed(true).criticalIssues(0)
                .judgeVersion("judge-v1").build();

        List<EvalSampleResult> samples = new LiveEvalRunner().run(List.of(evalCase), 1, ignored -> {
            if (calls.incrementAndGet() < 3) throw new EvalInfrastructureException("429");
            return execution(evalCase);
        }, judge);

        assertEquals(3, calls.get());
        assertEquals(EvalHarnessResult.Status.PASS, samples.get(0).getStatus());
    }

    @Test
    public void shouldEstimatePerCaseTsrAndExcludeUnavailableAndErrors() {
        List<EvalSampleResult> samples = new ArrayList<>();
        samples.add(sample("a", true, EvalHarnessResult.Status.PASS));
        samples.add(sample("a", false, EvalHarnessResult.Status.FAIL));
        samples.add(sample("b", true, EvalHarnessResult.Status.PASS));
        samples.add(sample("c", false, EvalHarnessResult.Status.ERROR));
        samples.add(sample("d", false, EvalHarnessResult.Status.UNAVAILABLE));

        EvalStatisticalReport report = new EvalStatisticsService().summarize(samples, 2, 0.25);

        assertEquals(EvalStatisticalReport.Decision.READY, report.getDecision());
        assertEquals(2, report.getEligibleCases());
        assertEquals(0.75, report.getTsrAtOne(), 0.0001);
        assertEquals(0.5, report.getPerCaseSuccessProbability().get("a"), 0.0001);
    }

    @Test
    public void shouldBlockOnlyWhenThePairedUpperBoundCrossesTheRegressionThreshold() {
        List<EvalSampleResult> baseline = List.of(sample("a", true, EvalHarnessResult.Status.PASS), sample("b", true, EvalHarnessResult.Status.PASS));
        List<EvalSampleResult> candidate = List.of(sample("a", false, EvalHarnessResult.Status.FAIL), sample("b", false, EvalHarnessResult.Status.FAIL));

        EvalStatisticalReport.Comparison comparison = new EvalStatisticsService().compare(baseline, candidate, 2, 0.01);

        assertEquals(EvalStatisticalReport.Decision.READY, comparison.getDecision());
        assertTrue(comparison.isBlocked());
        assertEquals(-1D, comparison.getDelta(), 0.0001);
    }

    @Test
    public void judgeCalibrationMustMeetHumanReferenceThresholds() {
        EvalJudgeResult correct = EvalJudgeResult.builder().available(true).passed(false).criticalIssues(1).judgeVersion("judge-v1").build();
        JudgeCalibrationService.Report report = new JudgeCalibrationService().calibrate(
                List.of(new JudgeCalibrationService.Item(false, true, correct)), 1, 1D, 1D);

        assertTrue(report.isApproved());
        assertEquals("judge-v1", report.getJudgeVersion());
    }

    @Test
    public void shouldWriteCostAvailabilityAndConfidenceReports() throws Exception {
        EvalStatisticalReport report = new EvalStatisticsService().summarize(
                List.of(sample("a", true, EvalHarnessResult.Status.PASS)), 1, 0D);
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("live-eval-report-");

        new EvalStatisticalReportWriter().write(report, directory);

        assertTrue(java.nio.file.Files.readString(directory.resolve("statistical-report.md")).contains("TSR@1 estimate"));
        assertTrue(java.nio.file.Files.exists(directory.resolve("statistical-report.json")));
    }

    @Test
    public void uncalibratedJudgeMustBeUnavailableInsteadOfScored() {
        JudgeCalibrationService.Report calibration = JudgeCalibrationService.Report.builder()
                .judgeVersion("judge-v1").approved(false).build();
        IEvalJudge judge = new CalibratedEvalJudge(input -> EvalJudgeResult.builder()
                .available(true).passed(true).judgeVersion("judge-v1").build(), calibration);

        EvalJudgeResult result = judge.judge(null);

        assertFalse(result.isAvailable());
    }

    private EvalSampleResult sample(String id, boolean passed, EvalHarnessResult.Status status) {
        return EvalSampleResult.builder().caseId(id).status(status).passed(passed).latencyMs(10)
                .inputTokens(2).outputTokens(1).estimatedCost(0.01).build();
    }

    private EvalCaseDefinition caseDefinition(String id, boolean judgeRequired) {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("answer_only"); expected.setJudgeRequired(judgeRequired);
        expected.setMaxCriticalIssues(0); expected.setMaxMajorIssues(0);
        return EvalCaseDefinition.builder().caseId(id).caseVersion("1").datasetVersion("live-v1")
                .diagramType("none").input(Map.of("user", "hello")).expected(expected).build();
    }

    private EvalExecution execution(EvalCaseDefinition evalCase) {
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Greeting' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
        return EvalExecution.builder().evalCase(evalCase).trace(EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(EvalTrace.TaskOutcome.FULFILLED).routing(EvalTrace.Routing.builder().routeType("answer_only").build())
                .beforeCanvasHash("same").afterCanvasHash("same").build()).initialCanvasXml(xml).finalCanvasXml(xml)
                .inputTokens(10).outputTokens(5).estimatedCost(0.02).build();
    }
}
