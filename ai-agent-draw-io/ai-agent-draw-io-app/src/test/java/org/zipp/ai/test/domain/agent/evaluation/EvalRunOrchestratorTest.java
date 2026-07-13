package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class EvalRunOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-07-13T06:00:00Z");

    @Test
    public void oneCaseErrorIsIsolatedAndStartIsIdempotent() throws Exception {
        RunStore store = new RunStore();
        List<EvalCaseDefinition> cases = cases("answer-greeting.yaml", "create-customer-er.yaml", "edit-api-gateway.yaml");
        EvalRunOrchestrator orchestrator = orchestrator(store, Runnable::run, cases, gitSha -> evalCase -> {
            if ("create-customer-er-001".equals(evalCase.getCaseId())) throw new IllegalStateException("recorded factory failure");
            var execution = new ModeBReplayExecutionFactory(gitSha).create(evalCase);
            if ("regression-edit-success-unchanged-canvas-001".equals(evalCase.getCaseId())) {
                execution.setFinalCanvasXml(execution.getInitialCanvasXml());
            }
            return execution;
        });

        EvalRun first = orchestrator.start(command("start-1"));
        EvalRun second = orchestrator.start(command("start-1"));

        assertEquals(first.getId(), second.getId());
        assertEquals(EvalRunStatus.COMPLETED, orchestrator.get(first.getId()).getStatus());
        assertEquals(3, orchestrator.episodes(first.getId()).size());
        assertEquals(1, orchestrator.episodes(first.getId()).stream().filter(e -> e.getStatus() == EvalEpisodeStatus.ERROR).count());
        assertNotNull(orchestrator.get(first.getId()).getReportRef());
        assertTrue(orchestrator.get(first.getId()).getGraderManifestJson().contains("xml-integrity-v1"));
        assertEquals("full-agent-smoke", orchestrator.get(first.getId()).getProfileId());
        assertTrue(orchestrator.get(first.getId()).getProfileSnapshotJson().contains("credentialAlias"));
        assertEquals(64, orchestrator.get(first.getId()).getProfileConfigHash().length());
    }

    @Test
    public void cancellationBeforeWorkerStartLeavesNoEpisodes() {
        RunStore store = new RunStore();
        QueuedExecutor executor = new QueuedExecutor();
        EvalRunOrchestrator orchestrator = orchestrator(store, executor, List.of(), gitSha -> evalCase -> null);
        EvalRun run = orchestrator.start(command("cancel-1"));

        orchestrator.cancel(run.getId());
        executor.runQueued();

        assertEquals(EvalRunStatus.CANCELLED, orchestrator.get(run.getId()).getStatus());
        assertTrue(orchestrator.episodes(run.getId()).isEmpty());
    }

    @Test
    public void unresolvedDatasetTargetCannotStart() {
        IEvalDatasetCaseSource unresolved = (id, version, role) -> List.of(EvalCaseDefinition.builder()
                .caseId("legacy").caseVersion("1").build());
        EvalRunOrchestrator orchestrator = new EvalRunOrchestrator(new RunStore(), unresolved,
                new ArtifactStore(), Runnable::run, git -> evalCase -> null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        EvalControlPlaneException failure = assertThrows(EvalControlPlaneException.class,
                () -> orchestrator.start(command("ambiguous-target")));

        assertEquals(EvalControlPlaneErrorCode.TARGET_AMBIGUOUS, failure.getCode());
    }

    @Test
    public void retryOnlyReexecutesErrorAndNeverRetriesAgentFail() throws Exception {
        RunStore store = new RunStore();
        AtomicBoolean failInfrastructure = new AtomicBoolean(true);
        List<EvalCaseDefinition> cases = cases("regression-edit-success-with-unchanged-canvas.yaml", "create-customer-er.yaml");
        EvalRunOrchestrator orchestrator = orchestrator(store, Runnable::run, cases, gitSha -> evalCase -> {
            if ("create-customer-er-001".equals(evalCase.getCaseId()) && failInfrastructure.get()) {
                throw new IllegalStateException("temporary infrastructure failure");
            }
            var execution = new ModeBReplayExecutionFactory(gitSha).create(evalCase);
            if ("regression-edit-success-unchanged-canvas-001".equals(evalCase.getCaseId())) {
                execution.setFinalCanvasXml(execution.getInitialCanvasXml());
                execution.getTrace().setAfterCanvasHash(execution.getTrace().getBeforeCanvasHash());
            }
            return execution;
        });
        EvalRun run = orchestrator.start(command("retry-1"));
        EvalEpisode agentFail = orchestrator.episodes(run.getId()).stream()
                .filter(e -> e.getStatus() == EvalEpisodeStatus.FAIL).findFirst().orElseThrow();
        failInfrastructure.set(false);

        orchestrator.retryErrors(run.getId());

        EvalEpisode unchangedFail = store.findEpisode(agentFail.getId()).orElseThrow();
        EvalEpisode recovered = orchestrator.episodes(run.getId()).stream()
                .filter(e -> "create-customer-er-001".equals(e.getCaseId())).findFirst().orElseThrow();
        assertEquals(1, unchangedFail.getAttempt());
        assertEquals(EvalEpisodeStatus.FAIL, unchangedFail.getStatus());
        assertEquals(2, recovered.getAttempt());
        assertEquals(EvalEpisodeStatus.PASS, recovered.getStatus());
    }

    @Test
    public void modeBPersistsTheDeterministicIssueSeverity() throws Exception {
        RunStore store = new RunStore();
        List<EvalCaseDefinition> definitions = cases("edit-api-gateway.yaml");
        EvalRunOrchestrator orchestrator = orchestrator(store, Runnable::run, definitions, gitSha -> evalCase -> {
            var execution = new ModeBReplayExecutionFactory(gitSha).create(evalCase);
            execution.setFinalCanvasXml("<mxGraphModel><root><mxCell id='0'></root>");
            return execution;
        });

        EvalRun run = orchestrator.start(command("severity-projection"));
        EvalEpisode episode = orchestrator.episodes(run.getId()).get(0);

        assertEquals(EvalEpisodeStatus.FAIL, episode.getStatus());
        assertEquals("critical", store.listGraders(episode.getId()).stream()
                .filter(value -> "xml_integrity".equals(value.getGraderName()))
                .findFirst().orElseThrow().getSeverity());
    }

    @Test
    public void allTwelveCoreCasesRunThroughTheDatasetOrchestrator() throws Exception {
        Path root = Path.of(Objects.requireNonNull(getClass().getResource("/evals/core-v1")).toURI());
        List<EvalCaseDefinition> definitions;
        try (var paths = Files.list(root)) {
            definitions = paths.filter(path -> path.toString().endsWith(".yaml")).sorted().map(path -> {
                try (var input = Files.newInputStream(path)) { return new EvalCaseLoader().load(input); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }).toList();
        }
        RunStore store = new RunStore();
        EvalRunOrchestrator orchestrator = orchestrator(store, Runnable::run, definitions,
                gitSha -> new ModeBReplayExecutionFactory(gitSha));

        EvalRun run = orchestrator.start(command("core-12"));

        assertEquals(12, definitions.size());
        assertEquals(12, orchestrator.episodes(run.getId()).size());
        assertEquals(EvalRunStatus.COMPLETED, orchestrator.get(run.getId()).getStatus());
    }

    @Test
    public void everyTargetDiskCasePersistsArtifactsAndIsolatesABadEpisode() throws Exception {
        List<TargetFixture> fixtures = List.of(
                new TargetFixture("full-agent.yaml", EvaluationTarget.FULL_AGENT, "full-agent-smoke"),
                new TargetFixture("router.yaml", EvaluationTarget.INTENT_ROUTER, "router-deterministic"),
                new TargetFixture("drawing.yaml", EvaluationTarget.DRAWING_QUALITY, "drawing-structure"));
        for (TargetFixture fixture : fixtures) {
            EvalCaseDefinition valid = targetCase(fixture.resource());
            EvalCaseDefinition invalid = targetCase(fixture.resource());
            invalidate(invalid, fixture.target());
            ArtifactStore artifacts = new ArtifactStore();
            RunStore store = new RunStore();
            IEvalDatasetCaseSource source = new IEvalDatasetCaseSource() {
                @Override public List<EvalCaseDefinition> loadPublished(String id, String version, EvalAdminRole role) {
                    return List.of(valid, invalid);
                }
                @Override public EvaluationTarget target(String id, String version, EvalAdminRole role) { return fixture.target(); }
            };
            EvalRunOrchestrator orchestrator = new EvalRunOrchestrator(store, source, artifacts, Runnable::run,
                    ModeBReplayExecutionFactory::new, Clock.fixed(NOW, ZoneOffset.UTC));

            EvalRun run = orchestrator.start(command("target-" + fixture.target(), fixture.profileId()));

            assertEquals(EvalRunStatus.COMPLETED, orchestrator.get(run.getId()).getStatus());
            assertEquals(1, orchestrator.episodes(run.getId()).stream()
                    .filter(episode -> episode.getStatus() == EvalEpisodeStatus.PASS).count());
            assertEquals(1, orchestrator.episodes(run.getId()).stream()
                    .filter(episode -> episode.getStatus() == EvalEpisodeStatus.ERROR).count());
            EvalEpisode passed = orchestrator.episodes(run.getId()).stream()
                    .filter(episode -> episode.getStatus() == EvalEpisodeStatus.PASS).findFirst().orElseThrow();
            assertTrue(artifacts.read(passed.getTraceRef()).isPresent());
            assertTrue(artifacts.read(orchestrator.get(run.getId()).getReportRef()).isPresent());
        }
    }

    private EvalRunOrchestrator orchestrator(RunStore store, IEvalJobExecutor executor,
                                             List<EvalCaseDefinition> definitions,
                                             java.util.function.Function<String, EvalBatchRunner.ExecutionFactory> factory) {
        IEvalDatasetCaseSource source = new IEvalDatasetCaseSource() {
            @Override public List<EvalCaseDefinition> loadPublished(String id, String version, EvalAdminRole role) {
                return definitions;
            }
            @Override public EvaluationTarget target(String id, String version, EvalAdminRole role) {
                return EvaluationTarget.FULL_AGENT;
            }
        };
        return new EvalRunOrchestrator(store, source, new ArtifactStore(), executor, factory,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EvalRunStartCommand command(String key) {
        return command(key, "full-agent-smoke");
    }

    private EvalRunStartCommand command(String key, String profileId) {
        return EvalRunStartCommand.builder().idempotencyKey(key).datasetId("core").datasetVersion("core-v1")
                .profileId(profileId).profileVersion("1")
                .repetitions(1).gitSha("candidate-sha").executionProfileHash("profile-v1")
                .createdBy("admin-1").build();
    }

    private EvalCaseDefinition targetCase(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream("/evals/targets-r4/" + resource)) {
            return new EvalCaseLoader().load(input);
        }
    }

    private void invalidate(EvalCaseDefinition evalCase, EvaluationTarget target) {
        evalCase.setCaseId(evalCase.getCaseId() + "-invalid");
        switch (target) {
            case FULL_AGENT -> evalCase.getReplay().setRouterReply(null);
            case INTENT_ROUTER -> evalCase.getReplay().getToolCalls().add(EvalCaseDefinition.ReplayToolCall.builder()
                    .name("modify_diagram").mode("append").cells("<mxCell id='9'/>").build());
            case DRAWING_QUALITY -> evalCase.getReplay().getToolCalls().get(0).setCells(null);
        }
    }

    private record TargetFixture(String resource, EvaluationTarget target, String profileId) { }

    private List<EvalCaseDefinition> cases(String... resources) throws Exception {
        List<EvalCaseDefinition> values = new ArrayList<>();
        for (String resource : resources) {
            try (var input = getClass().getResourceAsStream("/evals/core-v1/" + resource)) {
                EvalCaseDefinition value = new EvalCaseLoader().load(input);
                value.setEvaluationTarget(EvaluationTarget.FULL_AGENT);
                values.add(value);
            }
        }
        return values;
    }

    private static final class QueuedExecutor implements IEvalJobExecutor {
        private Runnable queued;
        @Override public void execute(Runnable job) { queued = job; }
        void runQueued() { queued.run(); }
    }

    private static final class ArtifactStore implements IEvalRunArtifactStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        @Override public String put(String runId, String episodeId, String type, byte[] content) { String ref = runId + "/" + episodeId + "/" + type; values.put(ref, content); return ref; }
        @Override public Optional<byte[]> read(String ref) { return Optional.ofNullable(values.get(ref)); }
    }

    private static final class RunStore implements IEvalRunStore {
        private final Map<String, EvalRun> runs = new LinkedHashMap<>();
        private final Map<String, EvalEpisode> episodes = new LinkedHashMap<>();
        private final Map<String, List<EvalGraderResultRecord>> graders = new LinkedHashMap<>();
        @Override public void insertRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public void updateRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return runs.values().stream().filter(r -> key.equals(r.getIdempotencyKey())).findFirst(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return runs.values().stream().skip(offset).limit(limit).toList(); }
        @Override public void saveEpisode(EvalEpisode episode) { episodes.put(episode.getId(), episode); }
        @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.ofNullable(episodes.get(id)); }
        @Override public List<EvalEpisode> listEpisodes(String runId) { return episodes.values().stream().filter(e -> runId.equals(e.getEvalRunId())).toList(); }
        @Override public void replaceGraders(String episodeId, List<EvalGraderResultRecord> values) { graders.put(episodeId, List.copyOf(values)); }
        @Override public List<EvalGraderResultRecord> listGraders(String episodeId) { return graders.getOrDefault(episodeId, List.of()); }
    }
}
