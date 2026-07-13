package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;

import java.util.*;

import static org.junit.Assert.*;

public class EvalTargetReportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void routerReportUsesEpisodeEvidenceAndMarksSmallSamplesExplicitly() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("router-run", EvaluationTarget.INTENT_ROUTER, 2, 4));
        List<EvalCaseDefinition> definitions = List.of(routerCase("create", "create_new"), routerCase("edit", "edit_existing"));
        addExecution(store, artifacts, "router-run", "create", 0, EvalEpisodeStatus.PASS, 10, "create_new");
        addExecution(store, artifacts, "router-run", "create", 1, EvalEpisodeStatus.PASS, 20, "create_new");
        addExecution(store, artifacts, "router-run", "edit", 0, EvalEpisodeStatus.PASS, 30, "edit_existing");
        addExecution(store, artifacts, "router-run", "edit", 1, EvalEpisodeStatus.FAIL, 40, null);

        EvalTargetReport report = service(store, artifacts, definitions).report("router-run", EvalAdminRole.ADMIN);

        assertEquals(0.75D, report.getRouter().getAccuracy(), 0.001D);
        assertEquals(1, report.getRouter().getInvalidCount());
        assertEquals(EvalTargetReport.Availability.AVAILABLE, report.getRouter().getMacroF1Availability());
        assertNotNull(report.getRouter().getMacroF1());
        assertEquals(0.5D, report.getRouter().getRepeatStability(), 0.001D);
        assertEquals(3, report.getRouter().getConfusionMatrix().size());
        assertEquals(Long.valueOf(25), report.getLatency().getMedianMs());
        assertEquals(Long.valueOf(40), report.getLatency().getP95Ms());

        EvalRun strict = run("small-run", EvaluationTarget.INTENT_ROUTER, 20, 100);
        store.insertRun(strict);
        addExecution(store, artifacts, "small-run", "create", 0, EvalEpisodeStatus.PASS, 10, "create_new");
        EvalTargetReport small = service(store, artifacts, definitions).report("small-run", EvalAdminRole.ADMIN);
        assertEquals(EvalTargetReport.Availability.COUNT_ONLY, small.getRouter().getMacroF1Availability());
        assertNull(small.getRouter().getMacroF1());
        assertEquals(EvalTargetReport.Availability.COUNT_ONLY, small.getLatency().getP95Availability());
        assertNull(small.getLatency().getP95Ms());
    }

    @Test
    public void routerMissingArtifactIsUnavailableInsteadOfAnInvalidPrediction() {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("missing-router", EvaluationTarget.INTENT_ROUTER, 20, 100));
        store.saveEpisode(EvalEpisode.builder().id("missing-evidence").evalRunId("missing-router")
                .caseId("create").caseVersion("1").status(EvalEpisodeStatus.FAIL).build());

        EvalTargetReport.RouterMetrics router = service(store, artifacts, List.of(routerCase("create", "create_new")))
                .report("missing-router", EvalAdminRole.ADMIN).getRouter();

        assertEquals(0, router.getClassifiedCount());
        assertEquals(0, router.getInvalidCount());
        assertEquals(1, router.getEvidenceUnavailableCount());
        assertNull(router.getAccuracy());
    }

    @Test
    public void routerMissingGoldRouteIsUnavailableInsteadOfAnInvalidPrediction() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("missing-gold", EvaluationTarget.INTENT_ROUTER, 20, 100));
        EvalCaseDefinition missingGold = routerCase("create", null);
        addExecution(store, artifacts, "missing-gold", "create", 0, EvalEpisodeStatus.FAIL, 10, "create_new");

        EvalTargetReport.RouterMetrics router = service(store, artifacts, List.of(missingGold))
                .report("missing-gold", EvalAdminRole.ADMIN).getRouter();

        assertEquals(0, router.getClassifiedCount());
        assertEquals(0, router.getInvalidCount());
        assertEquals(1, router.getEvidenceUnavailableCount());
    }

    @Test
    public void drawingReportLayersGradersAndLinksBeforeAfterEvidence() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("drawing-run", EvaluationTarget.DRAWING_QUALITY, 20, 100));
        EvalCaseDefinition definition = drawingCase("draw");
        addExecution(store, artifacts, "drawing-run", "draw", 0, EvalEpisodeStatus.PASS, 10, "create_new");
        addExecution(store, artifacts, "drawing-run", "draw", 1, EvalEpisodeStatus.FAIL, 20, "create_new");
        store.replaceGraders("drawing-run-draw-0", List.of(grader("drawing-run-draw-0", "xml_integrity", EvalEpisodeStatus.PASS, "none"),
                grader("drawing-run-draw-0", "visual_quality", EvalEpisodeStatus.PASS, "none")));
        store.replaceGraders("drawing-run-draw-1", List.of(grader("drawing-run-draw-1", "xml_integrity", EvalEpisodeStatus.FAIL, "critical"),
                grader("drawing-run-draw-1", "visual_quality", EvalEpisodeStatus.UNAVAILABLE, "major")));
        store.saveJudge(EvalJudgeResultRecord.builder().episodeId("drawing-run-draw-0")
                .status(EvalEpisodeStatus.PASS).judgeVersion("judge-v1").build());

        EvalTargetReport.DrawingMetrics drawing = service(store, artifacts, List.of(definition))
                .report("drawing-run", EvalAdminRole.ADMIN).getDrawing();

        EvalTargetReport.GraderLayer xml = drawing.getLayers().stream()
                .filter(value -> "xml_integrity".equals(value.getGraderName())).findFirst().orElseThrow();
        assertEquals(0.5D, xml.getPassRate(), 0.001D);
        assertEquals(List.of("drawing-run-draw-1"), xml.getFailedEpisodeIds());
        assertEquals(1, drawing.getJudgeAvailableCount());
        assertEquals(1, drawing.getJudgeUnavailableCount());
        assertEquals(0, drawing.getJudgeNotRequiredCount());
        assertEquals(2, drawing.getEvidence().size());
        assertTrue(drawing.getEvidence().stream().allMatch(EvalTargetReport.DrawingEvidence::isBeforeAvailable));
        assertTrue(drawing.getIssueSeverities().stream().anyMatch(value -> "critical".equals(value.getSeverity())));
        EvalTargetReport.GraderLayer visual = drawing.getLayers().stream()
                .filter(value -> "visual_quality".equals(value.getGraderName())).findFirst().orElseThrow();
        assertEquals(1D, visual.getPassRate(), 0.001D);
        assertEquals(1, visual.getUnavailableCount());
        assertEquals(List.of("drawing-run-draw-1"), visual.getUnavailableEpisodeIds());
    }

    @Test
    public void drawingReportDoesNotCallAnUnrequestedJudgeUnavailable() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("structure-run", EvaluationTarget.DRAWING_QUALITY, 20, 100));
        EvalCaseDefinition structure = drawingCase("structure");
        structure.getExpected().setJudgeRequired(false);
        addExecution(store, artifacts, "structure-run", "structure", 0, EvalEpisodeStatus.PASS, 10, "create_new");

        EvalTargetReport.DrawingMetrics drawing = service(store, artifacts, List.of(structure))
                .report("structure-run", EvalAdminRole.ADMIN).getDrawing();

        assertEquals(0, drawing.getJudgeAvailableCount());
        assertEquals(0, drawing.getJudgeUnavailableCount());
        assertEquals(1, drawing.getJudgeNotRequiredCount());
        assertTrue(drawing.getLayers().stream().anyMatch(value -> "visual_quality".equals(value.getGraderName())
                && value.getUnavailableCount() == 1));
    }

    @Test
    public void fullAgentReportExcludesInfrastructureFailuresAndBuildsFailureFunnel() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("agent-run", EvaluationTarget.FULL_AGENT, 20, 100));
        EvalCaseDefinition definition = drawingCase("agent");
        addExecution(store, artifacts, "agent-run", "agent", 0, EvalEpisodeStatus.PASS, 10, "create_new");
        addExecution(store, artifacts, "agent-run", "agent", 1, EvalEpisodeStatus.FAIL, 20, "answer_only");
        store.saveEpisode(EvalEpisode.builder().id("agent-error").evalRunId("agent-run").caseId("agent")
                .caseVersion("1").repetition(2).status(EvalEpisodeStatus.ERROR).latencyMs(999).build());
        store.replaceGraders("agent-run-agent-0", List.of(grader("agent-run-agent-0", "xml_integrity", EvalEpisodeStatus.PASS, "none")));
        store.replaceGraders("agent-run-agent-1", List.of(grader("agent-run-agent-1", "xml_integrity", EvalEpisodeStatus.FAIL, "critical")));

        EvalTargetReport report = service(store, artifacts, List.of(definition)).report("agent-run", EvalAdminRole.ADMIN);

        assertEquals(2, report.getEligibleEpisodes());
        assertEquals(1, report.getExcludedErrors());
        assertEquals(0.5D, report.getFullAgent().getTsrAtOne(), 0.001D);
        assertEquals(Long.valueOf(20), report.getLatency().getMaxMs());
        EvalTargetReport.FunnelStage route = report.getFullAgent().getFunnel().stream()
                .filter(value -> "route".equals(value.getStage())).findFirst().orElseThrow();
        assertEquals(1, route.getPassedCount());
        assertEquals(List.of("agent-run-agent-1"), route.getFailedEpisodeIds());
        EvalTargetReport.FunnelStage tool = report.getFullAgent().getFunnel().stream()
                .filter(value -> "tool".equals(value.getStage())).findFirst().orElseThrow();
        assertEquals(1, tool.getEligibleCount());
        assertEquals(1, tool.getPassedCount());
    }

    @Test
    public void fullAgentReportNeverTurnsMissingQualityEvidenceIntoZeroPercent() {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("empty-agent", EvaluationTarget.FULL_AGENT, 20, 100));
        store.saveEpisode(EvalEpisode.builder().id("only-error").evalRunId("empty-agent").caseId("agent")
                .caseVersion("1").status(EvalEpisodeStatus.ERROR).build());

        EvalTargetReport.FullAgentMetrics metrics = service(store, artifacts, List.of(drawingCase("agent")))
                .report("empty-agent", EvalAdminRole.ADMIN).getFullAgent();

        assertEquals(EvalTargetReport.Availability.UNAVAILABLE, metrics.getAvailability());
        assertNull(metrics.getTsrAtOne());
        assertNull(metrics.getCiLower());
        assertNotNull(metrics.getUnavailableReason());
    }

    @Test
    public void fullAgentSmallSampleIsCountOnlyUntilProfileMinimumCasesIsMet() throws Exception {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("small-agent", EvaluationTarget.FULL_AGENT, 20, 100, 2));
        EvalCaseDefinition definition = drawingCase("agent");
        addExecution(store, artifacts, "small-agent", "agent", 0, EvalEpisodeStatus.PASS, 10, "create_new");

        EvalTargetReport.FullAgentMetrics metrics = service(store, artifacts, List.of(definition))
                .report("small-agent", EvalAdminRole.ADMIN).getFullAgent();

        assertEquals(EvalTargetReport.Availability.COUNT_ONLY, metrics.getAvailability());
        assertNull(metrics.getTsrAtOne());
        assertNull(metrics.getCiLower());
        assertTrue(metrics.getUnavailableReason().contains("at least 2 eligible Cases"));
    }

    @Test
    public void fullAgentFunnelKeepsMissingEvidenceUnavailable() {
        Store store = new Store(); Artifacts artifacts = new Artifacts();
        store.insertRun(run("missing-agent", EvaluationTarget.FULL_AGENT, 20, 100));
        store.saveEpisode(EvalEpisode.builder().id("missing-agent-episode").evalRunId("missing-agent")
                .caseId("agent").caseVersion("1").status(EvalEpisodeStatus.FAIL).build());

        EvalTargetReport.FunnelStage route = service(store, artifacts, List.of(drawingCase("agent")))
                .report("missing-agent", EvalAdminRole.ADMIN).getFullAgent().getFunnel().get(0);

        assertEquals(0, route.getEligibleCount());
        assertEquals(1, route.getUnavailableCount());
        assertEquals(List.of("missing-agent-episode"), route.getUnavailableEpisodeIds());
        assertNull(route.getPassRate());
    }

    private EvalTargetReportService service(Store store, Artifacts artifacts, List<EvalCaseDefinition> definitions) {
        return new EvalTargetReportService(store, (dataset, version, role) -> definitions, artifacts);
    }

    private EvalRun run(String id, EvaluationTarget target, int minClass, int p95) {
        return run(id, target, minClass, p95, 1);
    }

    private EvalRun run(String id, EvaluationTarget target, int minClass, int p95, int minimumCases) {
        String snapshot = "{\"config\":{\"gatePolicy\":{\"minSamplesPerClass\":" + minClass
                + ",\"minLatencySamplesForP95\":" + p95 + ",\"minimumCases\":" + minimumCases
                + ",\"maximumErrorRate\":1}}}";
        String graders = target == EvaluationTarget.DRAWING_QUALITY
                ? "[\"xml-integrity-v1\",\"visual-quality-v1\"]" : "[]";
        return EvalRun.builder().id(id).datasetId("core").datasetVersion("v1").evaluationTarget(target)
                .mode(EvalRunMode.MODE_B).profileSnapshotJson(snapshot).graderManifestJson(graders)
                .status(EvalRunStatus.COMPLETED).build();
    }

    private EvalCaseDefinition routerCase(String id, String route) {
        return EvalCaseDefinition.builder().caseId(id).caseVersion("1").evaluationTarget(EvaluationTarget.INTENT_ROUTER)
                .expected(EvalCaseDefinition.Expected.builder().routeType(route).build()).build();
    }

    private EvalCaseDefinition drawingCase(String id) {
        return EvalCaseDefinition.builder().caseId(id).caseVersion("1").evaluationTarget(EvaluationTarget.FULL_AGENT)
                .expected(EvalCaseDefinition.Expected.builder().routeType("create_new").requireCanvasChange(true)
                        .allowedMutationTools(List.of("modify_diagram"))
                        .taskOutcome(EvalTrace.TaskOutcome.FULFILLED).judgeRequired(true).build()).build();
    }

    private void addExecution(Store store, Artifacts artifacts, String run, String caseId, int repetition,
                              EvalEpisodeStatus status, long latency, String actualRoute) throws Exception {
        String id = run + "-" + caseId + "-" + repetition;
        EvalTrace trace = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(EvalTrace.TaskOutcome.FULFILLED)
                .routing(actualRoute == null ? null : EvalTrace.Routing.builder().routeType(actualRoute).build())
                .beforeCanvasHash("before").afterCanvasHash("after")
                .toolCalls(List.of(EvalTrace.ToolCall.builder().name("modify_diagram").status(EvalTrace.RunStatus.SUCCESS).build())).build();
        EvalExecution execution = EvalExecution.builder().trace(trace).initialCanvasXml("<mxGraphModel><root/></mxGraphModel>")
                .finalCanvasXml("<mxGraphModel><root><mxCell id=\"1\"/></root></mxGraphModel>").build();
        String ref = "artifact-" + id; artifacts.values.put(ref, mapper.writeValueAsBytes(execution));
        store.saveEpisode(EvalEpisode.builder().id(id).evalRunId(run).caseId(caseId).caseVersion("1")
                .repetition(repetition).status(status).traceRef(ref).latencyMs(latency).estimatedCost(0.01D).build());
    }

    private EvalGraderResultRecord grader(String episode, String name, EvalEpisodeStatus status, String severity) {
        return EvalGraderResultRecord.builder().episodeId(episode).graderName(name).graderVersion("v1")
                .status(status).severity(severity).build();
    }

    private static final class Artifacts implements IEvalRunArtifactStore {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public String put(String runId, String episodeId, String artifactType, byte[] content) { return null; }
        @Override public Optional<byte[]> read(String artifactRef) { return Optional.ofNullable(values.get(artifactRef)); }
    }

    private static final class Store implements IEvalRunStore {
        final Map<String, EvalRun> runs = new LinkedHashMap<>();
        final Map<String, EvalEpisode> episodes = new LinkedHashMap<>();
        final Map<String, List<EvalGraderResultRecord>> graders = new HashMap<>();
        final Map<String, EvalJudgeResultRecord> judges = new HashMap<>();
        @Override public void insertRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public void updateRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public Optional<EvalRun> findRun(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String idempotencyKey) { return Optional.empty(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return runs.values().stream().toList(); }
        @Override public void saveEpisode(EvalEpisode episode) { episodes.put(episode.getId(), episode); }
        @Override public Optional<EvalEpisode> findEpisode(String episodeId) { return Optional.ofNullable(episodes.get(episodeId)); }
        @Override public List<EvalEpisode> listEpisodes(String runId) { return episodes.values().stream().filter(value -> runId.equals(value.getEvalRunId())).toList(); }
        @Override public void replaceGraders(String episodeId, List<EvalGraderResultRecord> values) { graders.put(episodeId, values); }
        @Override public List<EvalGraderResultRecord> listGraders(String episodeId) { return graders.getOrDefault(episodeId, List.of()); }
        @Override public void saveJudge(EvalJudgeResultRecord judge) { judges.put(judge.getEpisodeId(), judge); }
        @Override public Optional<EvalJudgeResultRecord> findJudge(String episodeId) { return Optional.ofNullable(judges.get(episodeId)); }
    }
}
