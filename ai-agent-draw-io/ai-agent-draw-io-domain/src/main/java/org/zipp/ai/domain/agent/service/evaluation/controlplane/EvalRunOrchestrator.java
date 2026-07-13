package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Asynchronously orchestrates isolated deterministic Case × repetition episodes. */
@Service
public class EvalRunOrchestrator {
    public static final String MODE_B_GRADER_MANIFEST = "route-tool-v1,xml-integrity-v1,visual-quality-v1,graph-assertion-v1,semantic-preservation-v1,multi-turn-state-v1";
    private final IEvalRunStore store;
    private final IEvalDatasetCaseSource cases;
    private final IEvalRunArtifactStore artifacts;
    private final IEvalJobExecutor jobs;
    private final Function<String, EvalBatchRunner.ExecutionFactory> factories;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs) {
        this(store, cases, artifacts, jobs, ModeBReplayExecutionFactory::new, Clock.systemUTC());
    }

    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs,
                               Function<String, EvalBatchRunner.ExecutionFactory> factories, Clock clock) {
        this.store = store; this.cases = cases; this.artifacts = artifacts; this.jobs = jobs;
        this.factories = factories; this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalRun start(EvalRunStartCommand command) {
        validate(command);
        Optional<EvalRun> existing = store.findByIdempotencyKey(command.getIdempotencyKey());
        if (existing.isPresent()) return existing.get();
        EvalRun run = EvalRun.builder().id("erun_" + UUID.randomUUID()).mode(EvalRunMode.MODE_B)
                .datasetId(command.getDatasetId()).datasetVersion(command.getDatasetVersion())
                .executionProfileHash(command.getExecutionProfileHash()).idempotencyKey(command.getIdempotencyKey())
                .repetitions(command.getRepetitions()).gitSha(command.getGitSha())
                .graderManifestJson(JSON.toJSONString(MODE_B_GRADER_MANIFEST.split(",")))
                .status(EvalRunStatus.QUEUED).createdBy(command.getCreatedBy()).createdAt(clock.instant()).build();
        try {
            store.insertRun(run);
        } catch (RuntimeException concurrentInsert) {
            Optional<EvalRun> winner = store.findByIdempotencyKey(command.getIdempotencyKey());
            if (winner.isPresent()) return winner.get();
            throw concurrentInsert;
        }
        try {
            jobs.execute(() -> execute(run.getId()));
        } catch (RuntimeException e) {
            store.updateRun(run.toBuilder().status(EvalRunStatus.INFRA_ERROR).completedAt(clock.instant()).build());
            throw e;
        }
        return store.findRun(run.getId()).orElse(run);
    }

    public void execute(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.CANCELLED) return;
        run = run.toBuilder().status(EvalRunStatus.RUNNING).startedAt(clock.instant()).build();
        store.updateRun(run);
        try {
            List<EvalCaseDefinition> definitions = cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(), EvalAdminRole.ADMIN);
            for (EvalCaseDefinition definition : definitions) {
                for (int repetition = 0; repetition < run.getRepetitions(); repetition++) {
                    if (cancelled(runId)) return;
                    executeEpisode(run, definition, repetition, 1);
                }
            }
            complete(runId);
        } catch (RuntimeException e) {
            EvalRun current = requireRun(runId);
            if (current.getStatus() != EvalRunStatus.CANCELLED) {
                store.updateRun(current.toBuilder().status(EvalRunStatus.INFRA_ERROR).completedAt(clock.instant()).build());
            }
        }
    }

    public EvalRun cancel(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.COMPLETED || run.getStatus() == EvalRunStatus.INFRA_ERROR) {
            throw new IllegalStateException("completed Eval Run cannot be cancelled");
        }
        EvalRun cancelled = run.toBuilder().status(EvalRunStatus.CANCELLED).completedAt(clock.instant()).build();
        store.updateRun(cancelled);
        return cancelled;
    }

    public EvalRun retryErrors(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getStatus() != EvalRunStatus.COMPLETED) throw new IllegalStateException("only a completed Eval Run can retry errors");
        List<EvalEpisode> errors = store.listEpisodes(runId).stream()
                .filter(episode -> episode.getStatus() == EvalEpisodeStatus.ERROR).toList();
        if (errors.isEmpty()) return run;
        Map<String, EvalCaseDefinition> definitions = new LinkedHashMap<>();
        for (EvalCaseDefinition definition : cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(), EvalAdminRole.ADMIN)) {
            definitions.put(caseKey(definition.getCaseId(), definition.getCaseVersion()), definition);
        }
        store.updateRun(run.toBuilder().status(EvalRunStatus.RUNNING).completedAt(null).build());
        for (EvalEpisode error : errors) {
            EvalCaseDefinition definition = definitions.get(caseKey(error.getCaseId(), error.getCaseVersion()));
            if (definition == null) throw new IllegalStateException("retry Case is missing from immutable Dataset Version");
            executeEpisode(run, definition, error.getRepetition(), error.getAttempt() + 1);
        }
        complete(runId);
        return requireRun(runId);
    }

    public EvalRun get(String runId) { return requireRun(runId); }
    public List<EvalRun> list(int limit, int offset) { return store.listRuns(Math.max(1, Math.min(limit, 200)), Math.max(0, offset)); }
    public List<EvalEpisode> episodes(String runId) { requireRun(runId); return store.listEpisodes(runId); }
    public List<EvalGraderResultRecord> graders(String episodeId) { return store.listGraders(episodeId); }

    private void executeEpisode(EvalRun run, EvalCaseDefinition definition, int repetition, int attempt) {
        long started = System.nanoTime();
        String episodeId = episodeId(run.getId(), definition.getCaseId(), definition.getCaseVersion(), repetition);
        try {
            EvalExecution execution = factories.apply(run.getGitSha()).create(definition);
            EvalHarnessResult result = new DefaultEvalHarness().evaluate(execution);
            String traceRef = artifacts.put(run.getId(), episodeId, "execution",
                    mapper.writeValueAsBytes(execution));
            EvalEpisodeStatus status = result.getStatus() == EvalHarnessResult.Status.PASS
                    ? EvalEpisodeStatus.PASS : result.getStatus() == EvalHarnessResult.Status.FAIL
                    ? EvalEpisodeStatus.FAIL : result.getStatus() == EvalHarnessResult.Status.UNAVAILABLE
                    ? EvalEpisodeStatus.UNAVAILABLE : EvalEpisodeStatus.ERROR;
            EvalEpisode episode = EvalEpisode.builder().id(episodeId).evalRunId(run.getId())
                    .caseId(definition.getCaseId()).caseVersion(definition.getCaseVersion())
                    .repetition(repetition).attempt(attempt).status(status).traceRef(traceRef).artifactRef(traceRef)
                    .latencyMs(result.getLatencyMs()).inputTokens(execution.getInputTokens())
                    .outputTokens(execution.getOutputTokens()).estimatedCost(execution.getEstimatedCost()).build();
            store.saveEpisode(episode);
            store.replaceGraders(episodeId, result.getGraders().stream().map(grader -> EvalGraderResultRecord.builder()
                    .episodeId(episodeId).graderName(grader.getGraderName()).graderVersion(grader.getGraderVersion())
                    .status(grader.isPassed() ? EvalEpisodeStatus.PASS : EvalEpisodeStatus.FAIL)
                    .severity(grader.isPassed() ? "none" : "major").evidenceJson(JSON.toJSONString(grader.getEvidence())).build()).toList());
        } catch (Exception e) {
            store.saveEpisode(EvalEpisode.builder().id(episodeId).evalRunId(run.getId())
                    .caseId(definition.getCaseId()).caseVersion(definition.getCaseVersion())
                    .repetition(repetition).attempt(attempt).status(EvalEpisodeStatus.ERROR)
                    .latencyMs((System.nanoTime() - started) / 1_000_000).errorClass(e.getClass().getSimpleName())
                    .errorMessage(e.getMessage()).build());
            store.replaceGraders(episodeId, List.of());
        }
    }

    private void complete(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getStatus() == EvalRunStatus.CANCELLED) return;
        try {
            EvalRun completed = run.toBuilder().status(EvalRunStatus.COMPLETED).completedAt(clock.instant()).build();
            Map<String, Object> report = Map.of("run", completed, "episodes", store.listEpisodes(runId));
            String reportRef = artifacts.put(runId, "report", "report", mapper.writeValueAsBytes(report));
            store.updateRun(completed.toBuilder().reportRef(reportRef).build());
        } catch (Exception e) {
            store.updateRun(run.toBuilder().status(EvalRunStatus.INFRA_ERROR).completedAt(clock.instant()).build());
        }
    }

    private boolean cancelled(String runId) { return requireRun(runId).getStatus() == EvalRunStatus.CANCELLED; }
    private EvalRun requireRun(String id) { return store.findRun(id).orElseThrow(() -> new IllegalArgumentException("Eval Run not found")); }
    private String episodeId(String runId, String caseId, String version, int repetition) { return "eep_" + UUID.nameUUIDFromBytes((runId + "|" + caseId + "|" + version + "|" + repetition).getBytes(StandardCharsets.UTF_8)); }
    private String caseKey(String caseId, String version) { return caseId + "@" + version; }
    private void validate(EvalRunStartCommand command) {
        if (command == null || blank(command.getIdempotencyKey()) || blank(command.getDatasetId())
                || blank(command.getDatasetVersion()) || blank(command.getGitSha()) || blank(command.getCreatedBy())) {
            throw new IllegalArgumentException("idempotencyKey, dataset, gitSha, and createdBy are required");
        }
        if (command.getRepetitions() < 1 || command.getRepetitions() > 20) throw new IllegalArgumentException("repetitions must be between 1 and 20");
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
