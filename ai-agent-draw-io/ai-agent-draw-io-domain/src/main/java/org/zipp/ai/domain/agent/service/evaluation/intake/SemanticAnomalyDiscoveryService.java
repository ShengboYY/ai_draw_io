package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRunStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticSamplingPolicy;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalJobExecutor;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Asynchronous, fail-closed semantic discovery. It creates review candidates and no workflow decisions. */
@Service
public class SemanticAnomalyDiscoveryService {
    private static final double MIN_CANDIDATE_CONFIDENCE = 0.5D;
    private static final double HIGH_CONFIDENCE = 0.8D;
    private static final Pattern PRODUCTION_ID = Pattern.compile("(?i)\\b(?:run|aru|usr|ecc|adt|req|diagram)[_-][A-Za-z0-9_-]+\\b");

    private final IAgentUsageTelemetryStore telemetryStore;
    private final ITraceToEvalStore store;
    private final AgentDebugTraceService debugTraceService;
    private final ISemanticAnomalyMiner miner;
    private final IEvalJobExecutor jobs;
    private final ISemanticMinerCallExecutor modelCalls;
    private final boolean enabled;
    private final boolean calibrationApproved;
    private final String calibratedModelVersion;
    private final int maxSamples;
    private final double estimatedCostPerAnalysisUsd;
    private final Duration modelTimeout;
    private final Clock clock;
    private final SemanticTraceProjector projector = new SemanticTraceProjector();

    @Autowired
    public SemanticAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetryStore,
                                           ITraceToEvalStore store,
                                           AgentDebugTraceService debugTraceService,
                                           ISemanticAnomalyMiner miner,
                                           IEvalJobExecutor jobs,
                                           ISemanticMinerCallExecutor modelCalls,
                                           @Value("${zipp.evaluation.semantic-miner-enabled:false}") boolean enabled,
                                           @Value("${zipp.evaluation.semantic-miner-calibration-approved:false}") boolean calibrationApproved,
                                           @Value("${zipp.evaluation.semantic-miner-calibrated-version:unconfigured}") String calibratedModelVersion,
                                           @Value("${zipp.evaluation.semantic-miner-max-samples:50}") int maxSamples,
                                           @Value("${zipp.evaluation.semantic-miner-estimated-cost-per-analysis-usd:0}") double estimatedCostPerAnalysisUsd,
                                           @Value("${zipp.evaluation.semantic-miner-timeout-ms:30000}") long timeoutMs) {
        this(telemetryStore, store, debugTraceService, miner, jobs, enabled, calibrationApproved,
                calibratedModelVersion, maxSamples, estimatedCostPerAnalysisUsd, Clock.systemUTC(),
                modelCalls, Duration.ofMillis(Math.max(1L, timeoutMs)));
    }

    public SemanticAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetryStore,
                                           ITraceToEvalStore store,
                                           AgentDebugTraceService debugTraceService,
                                           ISemanticAnomalyMiner miner,
                                           IEvalJobExecutor jobs,
                                           boolean enabled,
                                           boolean calibrationApproved,
                                           String calibratedModelVersion,
                                           int maxSamples,
                                           double estimatedCostPerAnalysisUsd,
                                           Clock clock) {
        this(telemetryStore, store, debugTraceService, miner, jobs, enabled, calibrationApproved,
                calibratedModelVersion, maxSamples, estimatedCostPerAnalysisUsd, clock,
                (adapter, projection, timeout) -> adapter.analyze(projection), Duration.ofSeconds(30));
    }

    private SemanticAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetryStore,
                                            ITraceToEvalStore store,
                                            AgentDebugTraceService debugTraceService,
                                            ISemanticAnomalyMiner miner,
                                            IEvalJobExecutor jobs,
                                            boolean enabled,
                                            boolean calibrationApproved,
                                            String calibratedModelVersion,
                                            int maxSamples,
                                            double estimatedCostPerAnalysisUsd,
                                            Clock clock,
                                            ISemanticMinerCallExecutor modelCalls,
                                            Duration modelTimeout) {
        this.telemetryStore = telemetryStore;
        this.store = store;
        this.debugTraceService = debugTraceService;
        this.miner = miner;
        this.jobs = jobs;
        this.modelCalls = modelCalls;
        this.enabled = enabled;
        this.calibrationApproved = calibrationApproved;
        this.calibratedModelVersion = calibratedModelVersion;
        this.maxSamples = Math.max(1, maxSamples);
        this.estimatedCostPerAnalysisUsd = Math.max(0D, estimatedCostPerAnalysisUsd);
        this.modelTimeout = modelTimeout;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SemanticMinerRun start(SemanticSamplingPolicy policy, int requestedLimit, String actor,
                                  boolean purposeConfirmed, String ipAddress, String userAgent) {
        if (!purposeConfirmed) throw new IllegalArgumentException("semantic discovery purpose confirmation is required");
        if (policy == null) throw new IllegalArgumentException("samplingPolicy is required");
        if (StringUtils.isBlank(actor)) throw new IllegalArgumentException("actor is required");
        int limit = Math.max(1, Math.min(requestedLimit, maxSamples));
        SemanticMinerRun run = SemanticMinerRun.builder().id("smr_" + UUID.randomUUID())
                .status(SemanticMinerRunStatus.QUEUED).samplingPolicy(policy).requestedLimit(limit)
                .sampledCount(0).analyzedCount(0).candidateCount(0).errorCount(0).estimatedCostUsd(0D)
                .modelVersion(miner.version()).sanitizerVersion(EvalDraftSanitizer.VERSION)
                .createdBy(actor).createdAt(clock.instant()).build();
        String unavailable = readinessFailure();
        if (unavailable != null) {
            run.setStatus(SemanticMinerRunStatus.UNAVAILABLE);
            run.setAvailabilityReason(unavailable);
            run.setCompletedAt(clock.instant());
            store.insertSemanticMinerRun(run);
            return run;
        }
        store.insertSemanticMinerRun(run);
        // The bounded Eval executor keeps model latency and provider failures off the production request path.
        try {
            jobs.execute(() -> execute(run, actor, ipAddress, userAgent));
        } catch (RuntimeException e) {
            run.setStatus(SemanticMinerRunStatus.COMPLETED_WITH_ERRORS);
            run.setAvailabilityReason("job_queue_rejected");
            run.setErrorCount(1);
            run.setCompletedAt(clock.instant());
            store.updateSemanticMinerRun(run);
        }
        return run;
    }

    public Optional<SemanticMinerRun> find(String runId) {
        if (StringUtils.isBlank(runId)) throw new IllegalArgumentException("semanticMinerRunId is required");
        return store.findSemanticMinerRun(runId);
    }

    public List<SemanticMinerRun> list(int requestedLimit) {
        return store.listSemanticMinerRuns(Math.max(1, Math.min(requestedLimit, 100)));
    }

    private void execute(SemanticMinerRun scan, String actor, String ipAddress, String userAgent) {
        scan.setStatus(SemanticMinerRunStatus.RUNNING);
        scan.setStartedAt(clock.instant());
        store.updateSemanticMinerRun(scan);
        int analyzed = 0;
        int candidates = 0;
        int errors = 0;
        try {
            List<AgentRunTelemetry> sampled = sample(scan.getSamplingPolicy(), scan.getRequestedLimit());
            scan.setSampledCount(sampled.size());
            for (AgentRunTelemetry run : sampled) {
                try {
                    AgentRunDetail detail = telemetryStore.findRunDetail(run.getId())
                            .orElseThrow(() -> new IllegalStateException("run detail unavailable"));
                    List<DebugTraceCapture> captures = debugTraceService.viewCapturesForRun(
                            actor, run.getId(), ipAddress, userAgent);
                    SemanticTraceProjector.Projection projection = projector.project(detail, captures);
                    if (!projection.safe()) {
                        errors++;
                        continue;
                    }
                    analyzed++;
                    ISemanticAnomalyMiner.Finding finding = modelCalls.execute(miner, projection.content(), modelTimeout);
                    if (persistFinding(run, finding)) candidates++;
                } catch (RuntimeException ignored) {
                    // A provider timeout, schema error, or unsafe sample is isolated to this one run.
                    errors++;
                }
            }
        } catch (RuntimeException ignored) {
            errors++;
        }
        scan.setAnalyzedCount(analyzed);
        scan.setCandidateCount(candidates);
        scan.setErrorCount(errors);
        scan.setEstimatedCostUsd(analyzed * estimatedCostPerAnalysisUsd);
        scan.setStatus(errors == 0 ? SemanticMinerRunStatus.COMPLETED : SemanticMinerRunStatus.COMPLETED_WITH_ERRORS);
        scan.setCompletedAt(clock.instant());
        store.updateSemanticMinerRun(scan);
    }

    private boolean persistFinding(AgentRunTelemetry run, ISemanticAnomalyMiner.Finding finding) {
        if (finding == null || !finding.isPotentialAnomaly() || !finding.requiresHumanReview()
                || finding.confidence() < MIN_CANDIDATE_CONFIDENCE) return false;
        String family = failureFamily(finding.failureFamily());
        List<String> evidence = safeEvidence(finding.evidence());
        if (evidence.isEmpty()) throw new IllegalArgumentException("model evidence is required");
        String summary = StringUtils.left(String.join("; ", evidence), 1024);
        Optional<EvalCaseCandidate> existing = store.findCandidateBySourceRunAndFailureFamily(run.getId(), family);
        if (existing.isPresent()) {
            store.mergeCandidateModelEvidence(existing.get().getId(), miner.version(), finding.confidence(), evidence, summary);
            return false;
        }
        String risk = finding.confidence() < HIGH_CONFIDENCE ? "medium" : normalizedRisk(finding.suggestedRisk());
        EvalCaseCandidate candidate = EvalCaseCandidate.builder().id("ecc_" + UUID.randomUUID())
                .sourceRunId(run.getId()).sourceAgentId(run.getAgentId()).sourcePhase("semantic_analysis")
                .failureFamily(family).ruleId("semantic_miner").evidenceSummary(summary).risk(risk)
                .discoveredAt(clock.instant()).policyVersion("semantic-sampling-v1")
                .status(EvalCandidateStatus.DETECTED).createdBy("semantic-miner")
                .detectionSource("MODEL_DETECTED").modelVersion(miner.version())
                .modelConfidence(finding.confidence()).modelEvidence(evidence).build();
        store.insertCandidate(candidate);
        return true;
    }

    private List<String> safeEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        if (evidence.stream().filter(StringUtils::isNotBlank).anyMatch(value -> PRODUCTION_ID.matcher(value).find())) {
            throw new IllegalArgumentException("model evidence contains a production identifier");
        }
        EvalDraftSanitizer.Result result = new EvalDraftSanitizer().sanitize(evidence);
        if (!result.safeForModel() || !result.removedCategories().isEmpty()) {
            throw new IllegalArgumentException("unsafe model evidence");
        }
        return evidence.stream().filter(StringUtils::isNotBlank).map(value -> StringUtils.left(value, 256)).limit(8).toList();
    }

    private List<AgentRunTelemetry> sample(SemanticSamplingPolicy policy, int limit) {
        List<AgentRunTelemetry> terminal = telemetryStore.listRuns(null, null, null,
                        Math.min(500, Math.max(limit, limit * 4)), 0).stream()
                .filter(run -> run != null && StringUtils.isNotBlank(run.getId()))
                .filter(run -> !"RUNNING".equalsIgnoreCase(run.getStatus())).toList();
        if (policy == SemanticSamplingPolicy.TARGETED) return targeted(terminal, limit);
        if (policy == SemanticSamplingPolicy.RANDOM) return random(terminal, limit);
        int targetedLimit = Math.max(1, (limit + 1) / 2);
        Map<String, AgentRunTelemetry> mixed = new LinkedHashMap<>();
        targeted(terminal, targetedLimit).forEach(run -> mixed.put(run.getId(), run));
        random(terminal, limit).forEach(run -> {
            if (mixed.size() < limit) mixed.putIfAbsent(run.getId(), run);
        });
        return new ArrayList<>(mixed.values());
    }

    private List<AgentRunTelemetry> targeted(List<AgentRunTelemetry> runs, int limit) {
        return runs.stream().sorted(Comparator.comparingInt(this::targetScore).reversed()
                .thenComparing(run -> StringUtils.defaultString(run.getId()))).limit(limit).toList();
    }

    private List<AgentRunTelemetry> random(List<AgentRunTelemetry> runs, int limit) {
        // Stable hash ordering makes sampling reproducible without storing production identifiers in the scan record.
        return runs.stream().sorted(Comparator.comparingInt(run -> StringUtils.defaultString(run.getId()).hashCode()))
                .limit(limit).toList();
    }

    private int targetScore(AgentRunTelemetry run) {
        int score = "SUCCESS".equalsIgnoreCase(run.getStatus()) ? 100 : 0;
        if (run.getLatencyMs() != null && run.getLatencyMs() >= 30_000L) score += 40;
        if (run.getLlmCallCount() != null && run.getLlmCallCount() >= 3L) score += 20;
        if (run.getToolCallCount() != null && run.getToolCallCount() >= 3L) score += 20;
        return score;
    }

    private String readinessFailure() {
        if (!enabled) return "semantic_miner_disabled";
        if (!calibrationApproved) return "calibration_not_approved";
        if (StringUtils.isBlank(calibratedModelVersion) || "unconfigured".equalsIgnoreCase(calibratedModelVersion)) {
            return "calibrated_model_version_unconfigured";
        }
        if (StringUtils.containsIgnoreCase(miner.version(), "model=unconfigured")) {
            return "semantic_miner_model_unconfigured";
        }
        return calibratedModelVersion.equals(miner.version()) ? null : "calibrated_model_version_mismatch";
    }

    private String failureFamily(String value) {
        return switch (StringUtils.upperCase(StringUtils.trimToEmpty(value))) {
            case "FALSE_SUCCESS" -> "semantic_false_success";
            case "INTENT_MISMATCH" -> "semantic_intent_mismatch";
            case "CLARIFICATION_FAILURE" -> "semantic_clarification_failure";
            case "TRAJECTORY_WASTE" -> "semantic_trajectory_waste";
            default -> throw new IllegalArgumentException("unsupported semantic failure family");
        };
    }

    private String normalizedRisk(String value) {
        String risk = StringUtils.lowerCase(StringUtils.trimToEmpty(value));
        return switch (risk) { case "critical", "high", "medium", "low" -> risk; default -> "medium"; };
    }
}
