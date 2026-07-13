package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EvalLiveRunOrchestratorTest {
    @Test
    public void liveAndReleaseRunsPersistSamplesStatisticsComparisonAndGate() {
        Store store = new Store(); AtomicInteger providerCalls = new AtomicInteger();
        List<EvalCaseDefinition> definitions = List.of(definition("case-a"), definition("case-b"));
        IEvalLiveRunSupport live = new FakeLiveSupport(evalCase -> {
            if (providerCalls.incrementAndGet() <= 2) throw new EvalInfrastructureException("429");
            return execution(evalCase);
        });
        EvalRunOrchestrator service = service(store, definitions, live);

        EvalRun baseline = service.start(command("baseline", EvalRunMode.MODE_C, null));
        EvalRun release = service.start(command("candidate", EvalRunMode.RELEASE, baseline.getId()));

        assertEquals(EvalRunStatus.COMPLETED, service.get(release.getId()).getStatus());
        assertEquals(10, providerCalls.get());
        assertEquals(4, store.listEpisodes(release.getId()).size());
        assertEquals(8, store.judges.size());
        EvalLiveRunReport report = service.insights(release.getId());
        assertEquals(EvalStatisticalReport.Decision.READY, report.getStatistics().getDecision());
        assertEquals(EvalStatisticalReport.Decision.READY, report.getComparison().getDecision());
        assertEquals(EvalGateOutcome.PASS, store.gates.get(release.getId()).getOutcome());
    }

    @Test
    public void missingCalibrationSequesteredAndPairedEvidenceProduceNoDecision() throws Exception {
        Store store = new Store(); EvalLiveRunReadiness notReady = EvalLiveRunReadiness.builder()
                .providerCredentialReady(true).judgeCalibrationApproved(false).judgeVersion("judge-v1")
                .sequesteredCaseCount(0).minimumSequesteredCases(10).build();
        EvalRunOrchestrator service = service(store, List.of(definition("only-case")),
                new FakeLiveSupport(EvalLiveRunOrchestratorTest::execution, notReady));

        EvalRun release = service.start(command("not-ready", EvalRunMode.RELEASE, null));

        assertEquals(EvalGateOutcome.NO_DECISION, store.gates.get(release.getId()).getOutcome());
        assertEquals(EvalStatisticalReport.Decision.NO_DECISION, service.insights(release.getId()).getComparison().getDecision());
        assertThrows(IllegalStateException.class, () -> service.overrideGate(release.getId(), "owner", "missing evidence"));
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(service.insights(release.getId())).contains("hello"));
    }

    @Test
    public void unsupportedMultiTurnIsAnEpisodeErrorNotAnAgentFailure() {
        EvalCaseDefinition multiTurn = definition("multi-turn");
        multiTurn.setInput(Map.of("turns", List.of(Map.of("user", "first"), Map.of("user", "second"))));
        Store store = new Store(); EvalRunOrchestrator service = service(store, List.of(multiTurn),
                new FakeLiveSupport(EvalLiveRunOrchestratorTest::execution));

        EvalRun run = service.start(command("multi", EvalRunMode.MODE_C, null));

        EvalEpisode episode = store.listEpisodes(run.getId()).get(0);
        assertEquals(EvalEpisodeStatus.ERROR, episode.getStatus());
        assertEquals("MultiTurnLiveAdapterUnsupported", episode.getErrorClass());
    }

    @Test
    public void deterministicFailureBlocksAndReleaseOwnerOverridePreservesOriginalEvidence() {
        Store store = new Store(); EvalRunOrchestrator service = service(store, List.of(definition("failed-case")),
                new FakeLiveSupport(EvalLiveRunOrchestratorTest::failedExecution));

        EvalRun release = service.start(command("blocked", EvalRunMode.RELEASE, null));
        EvalGateDecisionRecord original = service.gate(release.getId());
        EvalGateDecisionRecord overridden = service.overrideGate(release.getId(), "release-owner", "accepted known risk");

        assertEquals(EvalGateOutcome.BLOCK, original.getOutcome());
        assertEquals(EvalGateOutcome.BLOCK, overridden.getOutcome());
        assertTrue(service.insights(release.getId()).getGate().isOverrideApproved());
    }

    @Test
    public void liveRetryReexecutesOnlyInfrastructureErrorEpisodes() {
        AtomicInteger calls = new AtomicInteger(); Store store = new Store();
        EvalRunOrchestrator service = service(store, List.of(definition("retry-case")), new FakeLiveSupport(evalCase -> {
            if (calls.incrementAndGet() <= 3) throw new EvalInfrastructureException("503");
            return execution(evalCase);
        }));
        EvalRun run = service.start(command("retry-live", EvalRunMode.MODE_C, null));
        assertEquals(1, store.listEpisodes(run.getId()).stream().filter(value -> value.getStatus() == EvalEpisodeStatus.ERROR).count());

        service.retryErrors(run.getId());

        assertTrue(store.listEpisodes(run.getId()).stream().allMatch(value -> value.getStatus() == EvalEpisodeStatus.PASS));
        assertEquals(2, store.listEpisodes(run.getId()).stream().filter(value -> value.getRepetition() == 0).findFirst().orElseThrow().getAttempt());
    }

    private EvalRunOrchestrator service(Store store, List<EvalCaseDefinition> definitions, IEvalLiveRunSupport live) {
        return new EvalRunOrchestrator(store, (id, version, role) -> definitions, new Artifacts(), Runnable::run,
                git -> evalCase -> execution(evalCase), Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC), live);
    }

    private EvalRunStartCommand command(String key, EvalRunMode mode, String baseline) {
        return EvalRunStartCommand.builder().mode(mode).idempotencyKey(key).datasetId("core").datasetVersion("v1")
                .repetitions(2).gitSha(key + "-sha").candidateRef(key + "-sha").baselineRef(baseline)
                .executionProfileHash("profile-v1").maxEstimatedCost(10D).minimumCases(2).maximumErrorRate(0D)
                .minimumPairedCases(2).regressionThreshold(0.01D).createdBy("admin").build();
    }

    private static EvalCaseDefinition definition(String id) {
        EvalCaseDefinition.ExecutionProfile profile = new EvalCaseDefinition.ExecutionProfile();
        profile.setProfileId("profile-v1"); profile.setModel("fake-live"); profile.setModelCredentialId("credential-1");
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("answer_only"); expected.setJudgeRequired(true); expected.setMaxCriticalIssues(0); expected.setMaxMajorIssues(0);
        return EvalCaseDefinition.builder().caseId(id).caseVersion("1").datasetVersion("v1").risk("high")
                .diagramType("none").evaluationTarget(EvaluationTarget.FULL_AGENT)
                .input(Map.of("user", "hello")).expected(expected).executionProfile(profile).build();
    }

    private static EvalExecution execution(EvalCaseDefinition definition) {
        return execution(definition, EvalTrace.TaskOutcome.FULFILLED);
    }

    private static EvalExecution execution(EvalCaseDefinition definition, EvalTrace.TaskOutcome outcome) {
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
        return EvalExecution.builder().evalCase(definition).trace(EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(outcome).routing(EvalTrace.Routing.builder().routeType("answer_only").build())
                .beforeCanvasHash("same").afterCanvasHash("same").build()).initialCanvasXml(xml).finalCanvasXml(xml)
                .inputTokens(10).outputTokens(5).estimatedCost(0.01D).build();
    }

    private static EvalExecution failedExecution(EvalCaseDefinition definition) {
        EvalExecution execution = execution(definition, EvalTrace.TaskOutcome.NOT_FULFILLED);
        execution.getTrace().getRouting().setRouteType("clarify");
        return execution;
    }

    private record FakeLiveSupport(LiveEvalRunner.LiveExecutionFactory factory, EvalLiveRunReadiness readiness) implements IEvalLiveRunSupport {
        private FakeLiveSupport(LiveEvalRunner.LiveExecutionFactory factory) { this(factory, ready()); }
        @Override public LiveEvalRunner.LiveExecutionFactory executionFactory(String candidateRef) { return factory; }
        @Override public IEvalJudge judge() { return new CalibratedEvalJudge(input -> EvalJudgeResult.builder()
                .available(true).passed(true).score(5).judgeVersion("judge-v1").evidence(List.of("ok")).build(),
                JudgeCalibrationService.Report.builder().approved(readiness.isJudgeCalibrationApproved()).judgeVersion("judge-v1").build()); }
        private static EvalLiveRunReadiness ready() { return EvalLiveRunReadiness.builder().providerCredentialReady(true)
                .judgeCalibrationApproved(true).calibrationVersion("cal-v1").judgeVersion("judge-v1")
                .sequesteredCaseCount(20).minimumSequesteredCases(10).build(); }
    }

    private static final class Artifacts implements IEvalRunArtifactStore {
        final Map<String, byte[]> values = new HashMap<>();
        @Override public String put(String run, String episode, String type, byte[] content) { String ref = run + "/" + episode + "/" + type; values.put(ref, content); return ref; }
        @Override public Optional<byte[]> read(String ref) { return Optional.ofNullable(values.get(ref)); }
    }

    private static final class Store implements IEvalRunStore {
        final Map<String, EvalRun> runs = new LinkedHashMap<>(); final Map<String, EvalEpisode> episodes = new LinkedHashMap<>();
        final Map<String, List<EvalGraderResultRecord>> graders = new HashMap<>(); final Map<String, EvalJudgeResultRecord> judges = new HashMap<>();
        final Map<String, EvalGateDecisionRecord> gates = new HashMap<>();
        @Override public void insertRun(EvalRun value) { runs.put(value.getId(), value); }
        @Override public void updateRun(EvalRun value) { runs.put(value.getId(), value); }
        @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return runs.values().stream().filter(value -> key.equals(value.getIdempotencyKey())).findFirst(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return runs.values().stream().toList(); }
        @Override public void saveEpisode(EvalEpisode value) { episodes.put(value.getId(), value); }
        @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.ofNullable(episodes.get(id)); }
        @Override public List<EvalEpisode> listEpisodes(String runId) { return episodes.values().stream().filter(value -> runId.equals(value.getEvalRunId())).toList(); }
        @Override public void replaceGraders(String id, List<EvalGraderResultRecord> values) { graders.put(id, values); }
        @Override public List<EvalGraderResultRecord> listGraders(String id) { return graders.getOrDefault(id, List.of()); }
        @Override public void saveJudge(EvalJudgeResultRecord value) { judges.put(value.getEpisodeId(), value); }
        @Override public Optional<EvalJudgeResultRecord> findJudge(String episodeId) { return Optional.ofNullable(judges.get(episodeId)); }
        @Override public void saveGate(EvalGateDecisionRecord value) { gates.put(value.getEvalRunId(), value); }
        @Override public Optional<EvalGateDecisionRecord> findGate(String runId) { return Optional.ofNullable(gates.get(runId)); }
    }
}
