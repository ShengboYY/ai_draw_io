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
import org.zipp.ai.domain.agent.service.evaluation.target.EvalTargetExecutionAdapter;
import org.zipp.ai.domain.agent.service.evaluation.target.EvalTargetExecutionAdapters;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Asynchronously owns Run lifecycle and delegates Mode-specific Case × repetition execution. */
@Service
public class EvalRunOrchestrator {
    private final IEvalRunStore store;
    private final IEvalDatasetCaseSource cases;
    private final IEvalRunArtifactStore artifacts;
    private final IEvalJobExecutor jobs;
    private final Function<String, EvalBatchRunner.ExecutionFactory> factories;
    private final Clock clock;
    private final EvalLiveRunService liveRuns;
    private final EvaluationProfileResolver profiles;
    private final EvalTargetExecutionAdapters targetAdapters;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs,
                               Optional<IEvalLiveRunSupport> liveSupport, EvaluationProfileResolver profiles) {
        this(store, cases, artifacts, jobs, ModeBReplayExecutionFactory::new, Clock.systemUTC(),
                liveSupport.orElse(null), profiles);
    }

    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs,
                               Function<String, EvalBatchRunner.ExecutionFactory> factories, Clock clock) {
        this(store, cases, artifacts, jobs, factories, clock, null);
    }

    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs,
                               Function<String, EvalBatchRunner.ExecutionFactory> factories, Clock clock,
                               IEvalLiveRunSupport liveSupport) {
        this(store, cases, artifacts, jobs, factories, clock, liveSupport,
                new EvaluationProfileResolver(DefaultEvaluationProfiles::versions));
    }

    public EvalRunOrchestrator(IEvalRunStore store, IEvalDatasetCaseSource cases,
                               IEvalRunArtifactStore artifacts, IEvalJobExecutor jobs,
                               Function<String, EvalBatchRunner.ExecutionFactory> factories, Clock clock,
                               IEvalLiveRunSupport liveSupport, EvaluationProfileResolver profiles) {
        this.store = store; this.cases = cases; this.artifacts = artifacts; this.jobs = jobs;
        this.factories = factories; this.clock = clock == null ? Clock.systemUTC() : clock;
        this.profiles = profiles;
        this.targetAdapters = new EvalTargetExecutionAdapters();
        this.liveRuns = new EvalLiveRunService(store, cases, artifacts, liveSupport, this.clock, profiles);
    }

    public EvalRun start(EvalRunStartCommand command) {
        validate(command);
        Optional<EvalRun> existing = store.findByIdempotencyKey(command.getIdempotencyKey());
        if (existing.isPresent()) return existing.get();
        EvalRunMode mode = command.getMode() == null ? EvalRunMode.MODE_B : command.getMode();
        EvalAdminRole datasetRole = mode == EvalRunMode.RELEASE ? EvalAdminRole.RELEASE_OWNER : EvalAdminRole.ADMIN;
        EvaluationTarget target = cases.target(command.getDatasetId(), command.getDatasetVersion(), datasetRole);
        if (target == null) {
            throw new EvalControlPlaneException(EvalControlPlaneErrorCode.TARGET_AMBIGUOUS,
                    "Dataset evaluationTarget is unresolved");
        }
        List<EvalCaseDefinition> definitions = cases.loadPublished(command.getDatasetId(), command.getDatasetVersion(), datasetRole);
        EvaluationProfileSnapshot profile = profiles.resolveForRun(command.getProfileId(), command.getProfileVersion(),
                target, mode, definitions);
        if (mode != EvalRunMode.MODE_B) profile = profiles.bindLiveRuntime(profile, liveRuns.readiness());
        EvaluationProfileResolver.EvaluationProfilePolicy policy = profiles.policy(profile);
        EvalRun run = EvalRun.builder().id("erun_" + UUID.randomUUID()).mode(mode)
                .datasetId(command.getDatasetId()).datasetVersion(command.getDatasetVersion())
                .evaluationTarget(target)
                .baselineRef(command.getBaselineRef()).candidateRef(blank(command.getCandidateRef()) ? command.getGitSha() : command.getCandidateRef())
                .profileId(profile.profileId()).profileVersion(profile.profileVersion())
                .profileSnapshotJson(profile.canonicalConfigJson()).profileConfigHash(profile.configHash())
                .executionProfileHash(profile.configHash()).idempotencyKey(command.getIdempotencyKey())
                .repetitions(profile.repetitions()).gitSha(command.getGitSha())
                .maxEstimatedCost(policy.maxEstimatedCost()).minimumCases(policy.minimumCases())
                .maximumErrorRate(policy.maximumErrorRate()).minimumPairedCases(policy.minimumPairedCases())
                .regressionThreshold(policy.regressionThreshold())
                .graderManifestJson(profiles.graderManifestJson(profile))
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
            List<EvalCaseDefinition> definitions = copyDefinitions(cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(),
                    run.getMode() == EvalRunMode.RELEASE ? EvalAdminRole.RELEASE_OWNER : EvalAdminRole.ADMIN));
            profiles.verifyLiveRuntime(run, liveRuns.readiness());
            profiles.materializeExecutionConfig(run, definitions);
            run = run.toBuilder().plannedEpisodes(definitions.size() * run.getRepetitions()).build();
            store.updateRun(run);
            if (run.getMode() != EvalRunMode.MODE_B) {
                EvalRun liveRun = run;
                liveRuns.execute(liveRun, definitions, () -> cancelled(liveRun.getId()));
                return;
            }
            for (EvalCaseDefinition definition : definitions) {
                for (int repetition = 0; repetition < run.getRepetitions(); repetition++) {
                    if (cancelled(runId)) return;
                    executeEpisode(run, definition, repetition, 1);
                }
            }
            completeModeB(runId);
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
        for (EvalCaseDefinition definition : copyDefinitions(cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(),
                run.getMode() == EvalRunMode.RELEASE ? EvalAdminRole.RELEASE_OWNER : EvalAdminRole.ADMIN))) {
            definitions.put(caseKey(definition.getCaseId(), definition.getCaseVersion()), definition);
        }
        profiles.verifyLiveRuntime(run, liveRuns.readiness());
        profiles.materializeExecutionConfig(run, new ArrayList<>(definitions.values()));
        store.updateRun(run.toBuilder().status(EvalRunStatus.RUNNING).completedAt(null).build());
        for (EvalEpisode error : errors) {
            EvalCaseDefinition definition = definitions.get(caseKey(error.getCaseId(), error.getCaseVersion()));
            if (definition == null) throw new IllegalStateException("retry Case is missing from immutable Dataset Version");
            if (run.getMode() == EvalRunMode.MODE_B) executeEpisode(run, definition, error.getRepetition(), error.getAttempt() + 1);
            else liveRuns.executeEpisode(run, definition, error.getRepetition(), error.getAttempt() + 1);
        }
        if (run.getMode() == EvalRunMode.MODE_B) completeModeB(runId); else liveRuns.finalizeRun(runId, new ArrayList<>(definitions.values()));
        return requireRun(runId);
    }

    public EvalRun get(String runId) { return requireRun(runId); }
    public List<EvalRun> list(int limit, int offset) { return store.listRuns(Math.max(1, Math.min(limit, 200)), Math.max(0, offset)); }
    public List<EvalEpisode> episodes(String runId) { requireRun(runId); return store.listEpisodes(runId); }
    public List<EvalGraderResultRecord> graders(String episodeId) { return store.listGraders(episodeId); }

    public EvalLiveRunReport insights(String runId) { return liveRuns.insights(runId); }
    public EvalGateDecisionRecord gate(String runId) { return liveRuns.gate(runId); }
    public EvalStatisticalReport.Comparison comparison(String runId) { return liveRuns.comparison(runId); }
    public EvalGateDecisionRecord evaluateGate(String runId) { return liveRuns.evaluateGate(runId); }
    public EvalGateDecisionRecord overrideGate(String runId, String actor, String reason) { return liveRuns.overrideGate(runId, actor, reason); }

    private void executeEpisode(EvalRun run, EvalCaseDefinition definition, int repetition, int attempt) {
        long started = System.nanoTime();
        String episodeId = episodeId(run.getId(), definition.getCaseId(), definition.getCaseVersion(), repetition);
        try {
            EvalTargetExecutionAdapter adapter = targetAdapters.require(run.getEvaluationTarget(), profiles.runnerAdapter(run));
            EvalExecution execution = adapter.executeModeB(definition, run.getGitSha(), factories.apply(run.getGitSha()));
            profiles.applyExecutionMetadata(run, execution);
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
                    .severity(grader.getSeverity()).evidenceJson(JSON.toJSONString(grader.getEvidence())).build()).toList());
        } catch (Exception e) {
            store.saveEpisode(EvalEpisode.builder().id(episodeId).evalRunId(run.getId())
                    .caseId(definition.getCaseId()).caseVersion(definition.getCaseVersion())
                    .repetition(repetition).attempt(attempt).status(EvalEpisodeStatus.ERROR)
                    .latencyMs((System.nanoTime() - started) / 1_000_000).errorClass(e.getClass().getSimpleName())
                    .errorMessage(e.getMessage()).build());
            store.replaceGraders(episodeId, List.of());
        }
    }

    private void completeModeB(String runId) {
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
    private EvalRun requireRun(String id) { return store.findRun(id).orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND, "Eval Run not found")); }
    private String episodeId(String runId, String caseId, String version, int repetition) { return "eep_" + UUID.nameUUIDFromBytes((runId + "|" + caseId + "|" + version + "|" + repetition).getBytes(StandardCharsets.UTF_8)); }
    private String caseKey(String caseId, String version) { return caseId + "@" + version; }
    private List<EvalCaseDefinition> copyDefinitions(List<EvalCaseDefinition> definitions) {
        // Runtime Profile projection must never mutate immutable Dataset artifacts or shared caches.
        return definitions.stream().map(definition -> mapper.convertValue(definition, EvalCaseDefinition.class)).toList();
    }
    private void validate(EvalRunStartCommand command) {
        if (command == null || blank(command.getIdempotencyKey()) || blank(command.getDatasetId())
                || blank(command.getDatasetVersion()) || blank(command.getGitSha()) || blank(command.getCreatedBy())) {
            throw new IllegalArgumentException("idempotencyKey, dataset, gitSha, and createdBy are required");
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
