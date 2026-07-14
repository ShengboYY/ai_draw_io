package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisItem;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJob;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJobView;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalJobExecutor;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persistent orchestration around existing deterministic, semantic and visual analyzers. */
@Service
public class TraceAnalysisJobService {
    private static final int MAX_ATTEMPTS = 2;

    private final ITraceAnalysisJobStore store;
    private final IAgentUsageTelemetryStore telemetry;
    private final DeterministicCandidateSelectorService deterministic;
    private final SemanticAnomalyDiscoveryService semantic;
    private final VisualAnomalyDiscoveryService visual;
    private final IEvalJobExecutor executor;
    private final int maxBatchItems;
    private final double maxReservedCostUsd;
    private final Clock clock;

    @Autowired
    public TraceAnalysisJobService(ITraceAnalysisJobStore store, IAgentUsageTelemetryStore telemetry,
                                   DeterministicCandidateSelectorService deterministic,
                                   SemanticAnomalyDiscoveryService semantic,
                                   VisualAnomalyDiscoveryService visual,
                                   IEvalJobExecutor executor,
                                   @Value("${zipp.evaluation.trace-analysis-max-batch-items:50}") int maxBatchItems,
                                   @Value("${zipp.evaluation.trace-analysis-max-reserved-cost-usd:5}") double maxReservedCostUsd) {
        this(store, telemetry, deterministic, semantic, visual, executor, maxBatchItems, maxReservedCostUsd, Clock.systemUTC());
    }

    TraceAnalysisJobService(ITraceAnalysisJobStore store, IAgentUsageTelemetryStore telemetry,
                            DeterministicCandidateSelectorService deterministic,
                            SemanticAnomalyDiscoveryService semantic,
                            VisualAnomalyDiscoveryService visual, IEvalJobExecutor executor,
                            int maxBatchItems, double maxReservedCostUsd, Clock clock) {
        this.store = store; this.telemetry = telemetry; this.deterministic = deterministic;
        this.semantic = semantic; this.visual = visual; this.executor = executor;
        this.maxBatchItems = Math.max(1, maxBatchItems);
        this.maxReservedCostUsd = Math.max(0D, maxReservedCostUsd);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public TraceAnalysisJobView startSingle(String sourceRunId, String analyzerType, String actor,
                                            boolean purposeConfirmed, String ipAddress, String userAgent) {
        if (StringUtils.isAnyBlank(sourceRunId, actor)) throw new IllegalArgumentException("sourceRunId and actor are required");
        requirePurpose(purposeConfirmed);
        String analyzer = analyzer(analyzerType);
        String configHash = configHash(analyzer);
        String key = hash("single|" + sourceRunId + "|" + analyzer + "|" + configHash);
        TraceAnalysisJob existing = store.findJobByIdempotencyKey(key).orElse(null);
        if (existing != null) return view(existing);
        return createAndQueue("SINGLE_TRACE", analyzer, analyzerVersion(analyzer), configHash, null, clock.instant(), key,
                List.of(sourceRunId), actor, ipAddress, userAgent);
    }

    public TraceAnalysisJobView startBatch(String analyzerType, String samplingPolicy, int requestedLimit,
                                           Instant traceSnapshotAt, String actor, boolean purposeConfirmed,
                                           String ipAddress, String userAgent) {
        return startBatch(analyzerType, samplingPolicy, requestedLimit, null, traceSnapshotAt,
                actor, purposeConfirmed, ipAddress, userAgent);
    }

    public TraceAnalysisJobView startBatch(String analyzerType, String samplingPolicy, int requestedLimit,
                                           Instant completedFrom, Instant completedTo, String actor,
                                           boolean purposeConfirmed, String ipAddress, String userAgent) {
        if (StringUtils.isBlank(actor)) throw new IllegalArgumentException("actor is required");
        requirePurpose(purposeConfirmed);
        String analyzer = analyzer(analyzerType);
        String policy = samplingPolicy(samplingPolicy);
        int limit = Math.max(1, Math.min(requestedLimit, maxBatchItems));
        Instant snapshot = completedTo == null ? clock.instant() : completedTo;
        if (snapshot.isAfter(clock.instant())) throw new IllegalArgumentException("completedTo cannot be in the future");
        if (completedFrom != null && completedFrom.isAfter(snapshot)) {
            throw new IllegalArgumentException("completedFrom cannot be after completedTo");
        }
        List<String> sourceRuns = sample(policy, limit, completedFrom, snapshot);
        if (sourceRuns.isEmpty()) throw new IllegalStateException("no terminal traces are available for this sample");
        // The selection hash proves which immutable snapshot population was sampled without copying run ids into Job metadata.
        String selectionHash = hash(String.join("|", sourceRuns));
        String definition = "{\"policy\":\"" + policy + "\",\"limit\":" + limit
                + ",\"completedFrom\":" + instantJson(completedFrom)
                + ",\"completedTo\":\"" + snapshot + "\""
                + ",\"selectionHash\":\"" + selectionHash + "\"}";
        String configHash = configHash(analyzer);
        // The selected population keeps retries idempotent while still allowing second-level latest selection.
        String key = hash("batch|" + policy + "|" + limit + "|" + completedFrom + "|" + completedTo
                + "|" + selectionHash + "|" + analyzer + "|" + configHash);
        TraceAnalysisJob existing = store.findJobByIdempotencyKey(key).orElse(null);
        if (existing != null) return view(existing);
        return createAndQueue("SAMPLE_BATCH", analyzer, analyzerVersion(analyzer), configHash, definition, snapshot, key,
                sourceRuns, actor, ipAddress, userAgent);
    }

    public TraceAnalysisJobView find(String jobId) {
        return view(store.findJob(jobId).orElseThrow(() -> new IllegalArgumentException("analysis job not found")));
    }

    public List<TraceAnalysisJob> list(int requestedLimit) {
        return store.listJobs(Math.max(1, Math.min(requestedLimit, 100)));
    }

    private TraceAnalysisJobView createAndQueue(String scope, String analyzer, String analyzerVersion, String configHash,
                                                String definition, Instant snapshot, String key,
                                                List<String> sourceRuns, String actor,
                                                String ipAddress, String userAgent) {
        double reservedCost = sourceRuns.size() * costPerAnalysis(analyzer);
        if (reservedCost > maxReservedCostUsd) throw new IllegalArgumentException("analysis budget exceeded");
        String jobId = "taj_" + UUID.randomUUID();
        TraceAnalysisJob job = TraceAnalysisJob.builder().id(jobId).scope(scope).analyzerType(analyzer)
                .analyzerVersion(analyzerVersion).analyzerConfigHash(configHash).sampleDefinitionJson(definition).traceSnapshotAt(snapshot)
                .idempotencyKey(key).status("QUEUED").totalItems(sourceRuns.size()).reservedCost(reservedCost)
                .createdBy(actor).createdAt(clock.instant()).build();
        List<TraceAnalysisItem> items = sourceRuns.stream().map(runId -> TraceAnalysisItem.builder()
                .id("tai_" + UUID.randomUUID()).jobId(jobId).sourceRunId(runId).analyzerType(analyzer)
                .status("QUEUED").attempt(0).build()).toList();
        TraceAnalysisJob persisted = store.insertIfAbsent(job, items);
        if (!jobId.equals(persisted.getId())) return view(persisted);
        try {
            executor.execute(() -> execute(job, items, actor, ipAddress, userAgent));
        } catch (RuntimeException rejected) {
            failQueuedJob(job, items, rejected);
        }
        return new TraceAnalysisJobView(job, items);
    }

    private void execute(TraceAnalysisJob job, List<TraceAnalysisItem> items, String actor,
                         String ipAddress, String userAgent) {
        job.setStatus("RUNNING"); job.setStartedAt(clock.instant()); store.updateJob(job);
        int succeeded = 0; int failed = 0; double actualCost = 0D;
        for (TraceAnalysisItem item : items) {
            long started = System.nanoTime();
            item.setStatus("RUNNING"); store.updateItem(item);
            AnalysisOutcome outcome = null;
            RuntimeException failure = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                item.setAttempt(attempt); store.updateItem(item);
                try {
                    outcome = analyze(item.getAnalyzerType(), item.getSourceRunId(), actor, ipAddress, userAgent);
                    validateOutcome(outcome.status());
                    failure = null;
                    break;
                } catch (RuntimeException error) {
                    failure = error;
                    if (!retryable(error) || attempt == MAX_ATTEMPTS) break;
                }
            }
            item.setLatencyMs((System.nanoTime() - started) / 1_000_000L);
            // Only an analyzer outcome can confirm chargeable work; readiness failures and unknown provider failures stay at zero.
            double itemCost = outcome == null ? 0D : outcome.estimatedCostUsd();
            item.setEstimatedCost(itemCost); actualCost += itemCost;
            item.setOutcomeStatus(outcome == null ? null : outcome.status());
            if (failure == null && outcome != null && successfulOutcome(outcome.status())) {
                item.setStatus("SUCCEEDED"); item.setCandidateId(outcome.candidateId());
                succeeded++;
            } else {
                item.setStatus("FAILED"); item.setErrorClass(failure == null ? "UNAVAILABLE" : failure.getClass().getSimpleName());
                item.setErrorMessage(StringUtils.left(failure == null ? outcome.reason() : failure.getMessage(), 512)); failed++;
            }
            store.updateItem(item);
        }
        job.setSucceededItems(succeeded); job.setFailedItems(failed); job.setActualCost(actualCost);
        job.setStatus(failed == 0 ? "SUCCEEDED" : succeeded > 0 && "SAMPLE_BATCH".equals(job.getScope()) ? "PARTIAL" : "FAILED");
        job.setCompletedAt(clock.instant()); store.updateJob(job);
    }

    private AnalysisOutcome analyze(String analyzer, String sourceRunId, String actor, String ipAddress, String userAgent) {
        if ("DETERMINISTIC".equals(analyzer)) {
            List<org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate> candidates = deterministic.discoverRun(sourceRunId);
            return new AnalysisOutcome(candidates.isEmpty() ? "NO_FINDING" : "CANDIDATE_CREATED", null,
                    candidates.isEmpty() ? null : candidates.get(0).getId(), 0D);
        }
        if ("LLM".equals(analyzer)) {
            SemanticAnomalyDiscoveryService.AnalysisResult result = semantic.analyzeRun(sourceRunId, actor, true, ipAddress, userAgent);
            return new AnalysisOutcome(result.status(), result.reason(), result.candidateId(), result.estimatedCostUsd());
        }
        VisualAnomalyDiscoveryService.Result result = visual.analyzeRun(sourceRunId, actor, true);
        return new AnalysisOutcome(result.status(), result.reason(), result.candidateId(), result.estimatedCostUsd());
    }

    private void failQueuedJob(TraceAnalysisJob job, List<TraceAnalysisItem> items, RuntimeException failure) {
        for (TraceAnalysisItem item : items) {
            item.setStatus("FAILED"); item.setErrorClass("QUEUE_REJECTED");
            item.setErrorMessage(StringUtils.left(failure.getMessage(), 512)); store.updateItem(item);
        }
        job.setStatus("FAILED"); job.setFailedItems(items.size()); job.setCompletedAt(clock.instant()); store.updateJob(job);
    }

    private TraceAnalysisJobView view(TraceAnalysisJob job) { return new TraceAnalysisJobView(job, store.listItems(job.getId())); }

    private List<String> sample(String policy, int limit, Instant completedFrom, Instant snapshot) {
        List<AgentRunTelemetry> terminal = telemetry.listTerminalRunsBetween(
                        completedFrom, snapshot, Math.min(500, limit * 5)).stream()
                .filter(run -> run != null && StringUtils.isNotBlank(run.getId()))
                .toList();
        Comparator<AgentRunTelemetry> latest = Comparator
                .comparing(AgentRunTelemetry::getCompletedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentRunTelemetry::getId, Comparator.reverseOrder());
        Comparator<AgentRunTelemetry> targeted = Comparator.comparingInt(this::targetScore).reversed()
                .thenComparing(latest);
        Comparator<AgentRunTelemetry> random = Comparator.comparingInt(run -> StringUtils.defaultString(run.getId()).hashCode());
        if ("LATEST".equals(policy)) return terminal.stream().sorted(latest).limit(limit).map(AgentRunTelemetry::getId).toList();
        if ("TARGETED".equals(policy)) return terminal.stream().sorted(targeted).limit(limit).map(AgentRunTelemetry::getId).toList();
        if ("RANDOM".equals(policy)) return terminal.stream().sorted(random).limit(limit).map(AgentRunTelemetry::getId).toList();
        List<String> result = new ArrayList<>(terminal.stream().sorted(targeted).limit((limit + 1L) / 2L).map(AgentRunTelemetry::getId).toList());
        terminal.stream().sorted(random).map(AgentRunTelemetry::getId).filter(id -> !result.contains(id)).limit(limit - result.size()).forEach(result::add);
        return result;
    }

    private int targetScore(AgentRunTelemetry run) {
        int score = "SUCCESS".equalsIgnoreCase(run.getStatus()) ? 10 : 100;
        if (run.getLatencyMs() != null && run.getLatencyMs() >= 30_000L) score += 40;
        if (run.getToolCallCount() != null && run.getToolCallCount() >= 3) score += 20;
        return score;
    }

    private String analyzer(String value) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(value), Locale.ROOT);
        if (!List.of("DETERMINISTIC", "LLM", "VLM").contains(normalized)) throw new IllegalArgumentException("unsupported analyzerType");
        return normalized;
    }

    private String samplingPolicy(String value) {
        String normalized = StringUtils.upperCase(StringUtils.defaultIfBlank(value, "LATEST"), Locale.ROOT);
        if (!List.of("LATEST", "TARGETED", "RANDOM", "MIXED").contains(normalized)) throw new IllegalArgumentException("unsupported samplingPolicy");
        return normalized;
    }

    private String instantJson(Instant value) { return value == null ? "null" : "\"" + value + "\""; }

    private double costPerAnalysis(String analyzer) {
        return "LLM".equals(analyzer) ? semantic.estimatedCostPerAnalysisUsd()
                : "VLM".equals(analyzer) ? visual.estimatedCostPerAnalysisUsd() : 0D;
    }

    private String configHash(String analyzer) {
        return hash(analyzer + "|" + analyzerVersion(analyzer) + "|r6");
    }

    private String analyzerVersion(String analyzer) {
        return "LLM".equals(analyzer) ? semantic.analyzerVersion()
                : "VLM".equals(analyzer) ? visual.analyzerVersion() : "candidate-selector-v1";
    }

    private boolean successfulOutcome(String status) {
        return !"UNAVAILABLE".equals(status);
    }

    private void validateOutcome(String status) {
        if (!List.of("NO_FINDING", "CANDIDATE_CREATED", "MERGED", "UNAVAILABLE")
                .contains(StringUtils.defaultString(status))) {
            throw new IllegalStateException("unsupported analysis outcome: " + status);
        }
    }

    private boolean retryable(RuntimeException error) {
        String value = (error.getClass().getSimpleName() + " " + StringUtils.defaultString(error.getMessage())).toLowerCase(Locale.ROOT);
        return value.contains("timeout") || value.contains("429") || value.contains("network") || value.contains("connect");
    }

    private void requirePurpose(boolean purposeConfirmed) {
        if (!purposeConfirmed) throw new IllegalArgumentException("trace analysis purpose confirmation is required");
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private record AnalysisOutcome(String status, String reason, String candidateId, double estimatedCostUsd) { }
}
