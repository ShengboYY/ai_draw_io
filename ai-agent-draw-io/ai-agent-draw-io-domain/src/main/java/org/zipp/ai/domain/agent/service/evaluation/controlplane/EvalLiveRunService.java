package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Deep module for live sampling, statistics, paired comparison and Release Gate evidence. */
public class EvalLiveRunService {
    private final IEvalRunStore store;
    private final IEvalDatasetCaseSource cases;
    private final IEvalRunArtifactStore artifacts;
    private final IEvalLiveRunSupport support;
    private final Clock clock;
    private final EvaluationProfileResolver profiles;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public EvalLiveRunService(IEvalRunStore store, IEvalDatasetCaseSource cases, IEvalRunArtifactStore artifacts,
                              IEvalLiveRunSupport support, Clock clock, EvaluationProfileResolver profiles) {
        this.store = store; this.cases = cases; this.artifacts = artifacts; this.support = support; this.clock = clock;
        this.profiles = profiles;
    }

    public EvalLiveRunReadiness readiness() {
        return support == null ? EvalLiveRunReadiness.builder().build() : support.readiness();
    }

    public void execute(EvalRun run, List<EvalCaseDefinition> definitions, BooleanSupplier cancelled) {
        if (support == null) {
            for (EvalCaseDefinition definition : definitions) for (int repetition = 0; repetition < run.getRepetitions(); repetition++)
                unavailableEpisode(run, definition, repetition, "LiveProviderUnavailable");
            finalizeRun(run.getId(), definitions); return;
        }
        for (EvalCaseDefinition definition : definitions) {
            for (int repetition = 0; repetition < run.getRepetitions(); repetition++) {
                if (cancelled.getAsBoolean()) return;
                if (budgetExhausted(run)) unavailableEpisode(run, definition, repetition, "BudgetExceeded");
                else executeEpisode(run, definition, repetition, 1);
            }
        }
        finalizeRun(run.getId(), definitions);
    }

    public void executeEpisode(EvalRun run, EvalCaseDefinition definition, int repetition, int attempt) {
        String episodeId = episodeId(run.getId(), definition.getCaseId(), definition.getCaseVersion(), repetition);
        if (isUnsupportedMultiTurn(definition)) {
            errorEpisode(run, definition, repetition, attempt, "MultiTurnLiveAdapterUnsupported",
                    "multi-turn live session adapter is not configured"); return;
        }
        EvalLiveRunReadiness readiness = support == null ? null : support.readiness();
        if (readiness == null || !readiness.isProviderCredentialReady()) {
            unavailableEpisode(run, definition, repetition, "ProviderCredentialUnavailable"); return;
        }
        try {
            LiveEvalRunner.LiveExecutionFactory runtimeFactory = support.executionFactory(run.getCandidateRef());
            LiveEvalRunner.EpisodeResult result = new LiveEvalRunner(new DefaultEvalHarness(), support.diagramRenderer()).runDetailed(List.of(definition), 1,
                    evalCase -> {
                        EvalExecution execution = runtimeFactory.execute(evalCase);
                        profiles.applyExecutionMetadata(run, execution);
                        return execution;
                    }, support.judge()).get(0);
            EvalSampleResult sample = result.sample();
            String traceRef = result.execution() == null ? null : artifacts.put(run.getId(), episodeId, "execution",
                    mapper.writeValueAsBytes(result.execution()));
            store.saveEpisode(EvalEpisode.builder().id(episodeId).evalRunId(run.getId()).caseId(definition.getCaseId())
                    .caseVersion(definition.getCaseVersion()).repetition(repetition).attempt(attempt)
                    .status(EvalEpisodeStatus.valueOf(sample.getStatus().name())).traceRef(traceRef).artifactRef(traceRef)
                    .latencyMs(sample.getLatencyMs()).inputTokens(sample.getInputTokens()).outputTokens(sample.getOutputTokens())
                    .estimatedCost(sample.getEstimatedCost()).errorClass(sample.getErrorClass()).build());
            store.replaceGraders(episodeId, graderRecords(episodeId, result.deterministic()));
            if (result.judge() != null) store.saveJudge(judgeRecord(episodeId, result.judge(), readiness,
                    definition.getDiagramType() != null && !"none".equalsIgnoreCase(definition.getDiagramType())));
        } catch (Exception e) {
            errorEpisode(run, definition, repetition, attempt, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    public void finalizeRun(String runId, List<EvalCaseDefinition> definitions) {
        EvalRun run = requireRun(runId);
        EvalLiveRunReadiness readiness = readiness();
        profiles.verifyLiveRuntime(run, readiness);
        List<EvalSampleResult> samples = samples(store.listEpisodes(runId));
        EvalStatisticsService statistics = new EvalStatisticsService();
        EvalStatisticalReport report = statistics.summarize(samples, run.getMinimumCases(), run.getMaximumErrorRate());
        EvalStatisticalReport.Comparison comparison = comparison(run, samples, statistics);
        EvalGateDecisionRecord gate = run.getMode() == EvalRunMode.RELEASE
                ? evaluateGate(run, definitions, report, comparison, readiness) : null;
        try {
            EvalLiveRunReport liveReport = EvalLiveRunReport.builder().statistics(report).comparison(comparison)
                    .readiness(readiness).gate(gate).build();
            String ref = artifacts.put(runId, "report", "live-report", mapper.writeValueAsBytes(liveReport));
            store.updateRun(run.toBuilder().status(EvalRunStatus.COMPLETED).reportRef(ref).completedAt(clock.instant()).build());
        } catch (Exception e) {
            store.updateRun(run.toBuilder().status(EvalRunStatus.INFRA_ERROR).completedAt(clock.instant()).build());
        }
    }

    public EvalLiveRunReport insights(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getMode() == EvalRunMode.MODE_B || run.getReportRef() == null) throw new IllegalStateException("Live Eval report is unavailable");
        try {
            byte[] value = artifacts.read(run.getReportRef()).orElseThrow(() -> new IllegalStateException("Live Eval report artifact is unavailable"));
            EvalLiveRunReport report = mapper.readValue(value, EvalLiveRunReport.class);
            return EvalLiveRunReport.builder().statistics(report.getStatistics()).comparison(report.getComparison())
                    .readiness(report.getReadiness()).gate(store.findGate(runId).orElse(report.getGate())).build();
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Live Eval report artifact is invalid", e); }
    }

    public EvalGateDecisionRecord gate(String runId) {
        requireRun(runId); return store.findGate(runId).orElseThrow(() -> new IllegalStateException("Release Gate decision is unavailable"));
    }
    public EvalStatisticalReport.Comparison comparison(String runId) { return insights(runId).getComparison(); }

    public EvalGateDecisionRecord evaluateGate(String runId) {
        EvalRun run = requireRun(runId);
        if (run.getMode() != EvalRunMode.RELEASE || run.getStatus() != EvalRunStatus.COMPLETED)
            throw new IllegalStateException("only a completed Release Run can be evaluated");
        EvalLiveRunReport current = insights(runId);
        List<EvalCaseDefinition> definitions = cases.loadPublished(run.getDatasetId(), run.getDatasetVersion(), EvalAdminRole.RELEASE_OWNER);
        EvalLiveRunReadiness readiness = readiness();
        profiles.verifyLiveRuntime(run, readiness);
        return evaluateGate(run, definitions, current.getStatistics(), current.getComparison(), readiness);
    }

    public EvalGateDecisionRecord overrideGate(String runId, String actor, String reason) {
        if (blank(actor) || blank(reason)) throw new IllegalArgumentException("Release Owner and override reason are required");
        EvalGateDecisionRecord current = store.findGate(runId).orElseThrow(() -> new IllegalStateException("Release Gate decision is unavailable"));
        // Missing evidence remains NO_DECISION and can never be relabelled by an operator.
        if (current.getOutcome() != EvalGateOutcome.BLOCK) throw new IllegalStateException("only BLOCK can be overridden");
        EvalGateDecisionRecord updated = EvalGateDecisionRecord.builder().evalRunId(runId).gateVersion(current.getGateVersion())
                .outcome(current.getOutcome()).reasonsJson(current.getReasonsJson()).decidedAt(current.getDecidedAt())
                .overrideApproved(true).overrideReason(reason).overriddenBy(actor).overriddenAt(clock.instant()).build();
        store.saveGate(updated); return updated;
    }

    private EvalStatisticalReport.Comparison comparison(EvalRun run, List<EvalSampleResult> candidate,
                                                         EvalStatisticsService statistics) {
        if (blank(run.getBaselineRef())) return noComparison();
        Optional<EvalRun> baseline = store.findRun(run.getBaselineRef());
        if (baseline.isEmpty() || baseline.get().getMode() == EvalRunMode.MODE_B
                || !Objects.equals(baseline.get().getExecutionProfileHash(), run.getExecutionProfileHash())) return noComparison();
        return statistics.compare(samples(store.listEpisodes(baseline.get().getId())), candidate,
                run.getMinimumPairedCases(), run.getRegressionThreshold());
    }

    private EvalGateDecisionRecord evaluateGate(EvalRun run, List<EvalCaseDefinition> definitions,
            EvalStatisticalReport report, EvalStatisticalReport.Comparison comparison, EvalLiveRunReadiness readiness) {
        Map<String, EvalCaseDefinition> byId = new HashMap<>(); definitions.forEach(value -> byId.put(value.getCaseId(), value));
        List<EvalReleaseGateService.CaseResult> caseResults = store.listEpisodes(run.getId()).stream().map(episode -> {
            EvalHarnessResult result = EvalHarnessResult.builder().caseId(episode.getCaseId())
                    .status(EvalHarnessResult.Status.valueOf(episode.getStatus().name()))
                    .passed(episode.getStatus() == EvalEpisodeStatus.PASS).build();
            EvalCaseDefinition definition = byId.get(episode.getCaseId());
            return new EvalReleaseGateService.CaseResult(episode.getCaseId(), definition == null ? "unknown" : definition.getRisk(), result);
        }).toList();
        boolean judgeRequired = definitions.stream().anyMatch(value -> Boolean.TRUE.equals(value.getExpected().getJudgeRequired()));
        boolean textJudgeRequired = definitions.stream().anyMatch(value -> Boolean.TRUE.equals(value.getExpected().getJudgeRequired())
                && (value.getDiagramType() == null || "none".equalsIgnoreCase(value.getDiagramType())));
        boolean visualJudgeRequired = definitions.stream().anyMatch(value -> Boolean.TRUE.equals(value.getExpected().getJudgeRequired())
                && value.getDiagramType() != null && !"none".equalsIgnoreCase(value.getDiagramType()));
        JudgeCalibrationService.Report calibration = JudgeCalibrationService.Report.builder()
                .approved((!textJudgeRequired || readiness.isJudgeCalibrationApproved())
                        && (!visualJudgeRequired || readiness.isVisualJudgeCalibrationApproved()))
                .judgeVersion(visualJudgeRequired ? readiness.getVisualJudgeVersion() : readiness.getJudgeVersion()).build();
        EvalReleaseGateService.Decision decision = new EvalReleaseGateService().evaluate(new EvalReleaseGateService.Input(
                caseResults, report, comparison, calibration, judgeRequired, readiness.getSequesteredCaseCount(),
                readiness.getMinimumSequesteredCases()));
        EvalGateDecisionRecord value = EvalGateDecisionRecord.builder().evalRunId(run.getId()).gateVersion("release-gate-v1")
                .outcome(EvalGateOutcome.valueOf(decision.outcome().name())).reasonsJson(JSON.toJSONString(decision.reasons()))
                .decidedAt(clock.instant()).build();
        store.saveGate(value); return value;
    }

    private List<EvalGraderResultRecord> graderRecords(String episodeId, EvalHarnessResult deterministic) {
        if (deterministic == null) return List.of();
        return deterministic.getGraders().stream().map(grader -> EvalGraderResultRecord.builder().episodeId(episodeId)
                .graderName(grader.getGraderName()).graderVersion(grader.getGraderVersion())
                .status(grader.isPassed() ? EvalEpisodeStatus.PASS : EvalEpisodeStatus.FAIL)
                .severity(grader.isPassed() ? "none" : "major").evidenceJson(JSON.toJSONString(grader.getEvidence())).build()).toList();
    }

    private EvalJudgeResultRecord judgeRecord(String episodeId, EvalJudgeResult judge, EvalLiveRunReadiness readiness,
                                               boolean visual) {
        EvalEpisodeStatus status = !judge.isAvailable() ? EvalEpisodeStatus.UNAVAILABLE
                : judge.isPassed() ? EvalEpisodeStatus.PASS : EvalEpisodeStatus.FAIL;
        Map<String, Object> score = new LinkedHashMap<>(); score.put("score", judge.getScore());
        score.put("criticalIssues", judge.getCriticalIssues()); score.put("majorIssues", judge.getMajorIssues());
        score.put("confidence", judge.getConfidence());
        return EvalJudgeResultRecord.builder().episodeId(episodeId).judgeVersion(judge.getJudgeVersion())
                .calibrationVersion(visual ? readiness.getVisualCalibrationVersion() : readiness.getCalibrationVersion()).status(status)
                .scoreJson(JSON.toJSONString(score)).evidenceJson(JSON.toJSONString(judge.getEvidence())).build();
    }

    private List<EvalSampleResult> samples(List<EvalEpisode> episodes) {
        return episodes.stream().map(value -> EvalSampleResult.builder().caseId(value.getCaseId()).repetition(value.getRepetition())
                .status(EvalHarnessResult.Status.valueOf(value.getStatus().name())).passed(value.getStatus() == EvalEpisodeStatus.PASS)
                .latencyMs(value.getLatencyMs()).inputTokens(value.getInputTokens()).outputTokens(value.getOutputTokens())
                .estimatedCost(value.getEstimatedCost()).errorClass(value.getErrorClass()).build()).toList();
    }

    private void unavailableEpisode(EvalRun run, EvalCaseDefinition definition, int repetition, String reason) {
        store.saveEpisode(EvalEpisode.builder().id(episodeId(run.getId(), definition.getCaseId(), definition.getCaseVersion(), repetition))
                .evalRunId(run.getId()).caseId(definition.getCaseId()).caseVersion(definition.getCaseVersion())
                .repetition(repetition).attempt(1).status(EvalEpisodeStatus.UNAVAILABLE).errorClass(reason).errorMessage(reason).build());
    }

    private void errorEpisode(EvalRun run, EvalCaseDefinition definition, int repetition, int attempt,
                              String errorClass, String message) {
        String id = episodeId(run.getId(), definition.getCaseId(), definition.getCaseVersion(), repetition);
        store.saveEpisode(EvalEpisode.builder().id(id).evalRunId(run.getId()).caseId(definition.getCaseId())
                .caseVersion(definition.getCaseVersion()).repetition(repetition).attempt(attempt).status(EvalEpisodeStatus.ERROR)
                .errorClass(errorClass).errorMessage(message).build());
        store.replaceGraders(id, List.of());
    }

    private boolean budgetExhausted(EvalRun run) { return run.getMaxEstimatedCost() > 0D && store.listEpisodes(run.getId()).stream().mapToDouble(EvalEpisode::getEstimatedCost).sum() >= run.getMaxEstimatedCost(); }
    private boolean isUnsupportedMultiTurn(EvalCaseDefinition definition) { Object turns = definition.getInput() == null ? null : definition.getInput().get("turns"); return turns instanceof List<?> values && values.size() > 1; }
    private EvalStatisticalReport.Comparison noComparison() { return EvalStatisticalReport.Comparison.builder().decision(EvalStatisticalReport.Decision.NO_DECISION).build(); }
    private EvalRun requireRun(String id) { return store.findRun(id).orElseThrow(() -> new EvalControlPlaneException(EvalControlPlaneErrorCode.NOT_FOUND, "Eval Run not found")); }
    private String episodeId(String runId, String caseId, String version, int repetition) { return "eep_" + UUID.nameUUIDFromBytes((runId + "|" + caseId + "|" + version + "|" + repetition).getBytes(StandardCharsets.UTF_8)); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
