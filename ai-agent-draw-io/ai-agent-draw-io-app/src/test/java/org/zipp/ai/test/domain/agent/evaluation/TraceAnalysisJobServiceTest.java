package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.evaluation.intake.*;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

public class TraceAnalysisJobServiceTest {

    @Test
    public void singleTraceIsIdempotentAndPersistsLatencyFinding() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-slow", "SUCCESS", 42_000L);
        CandidateStore candidates = new CandidateStore();
        MemoryJobStore jobs = new MemoryJobStore();
        DeterministicCandidateSelectorService selector = new DeterministicCandidateSelectorService(telemetry, candidates);
        TraceAnalysisJobService service = new TraceAnalysisJobService(jobs, telemetry, selector,
                new StubSemantic(), new StubVisual(), Runnable::run, 50, 5D);

        TraceAnalysisJobView first = service.startSingle("run-slow", "DETERMINISTIC", "admin", true, null, null);
        TraceAnalysisJobView second = service.startSingle("run-slow", "DETERMINISTIC", "admin", true, null, null);

        assertEquals(first.job().getId(), second.job().getId());
        if (!"SUCCEEDED".equals(first.job().getStatus())) {
            fail(first.items().get(0).getErrorClass() + ": " + first.items().get(0).getErrorMessage());
        }
        assertEquals(1, first.job().getSucceededItems());
        assertEquals("latency", candidates.values.get(0).getFailureFamily());
        assertNotNull(first.items().get(0).getCandidateId());
    }

    @Test
    public void batchRetriesInfrastructureFailureAndKeepsSuccessfulItems() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-good", "SUCCESS", 10L);
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-timeout").status("FAILED").latencyMs(10L)
                .startedAt(Instant.parse("2020-01-02T00:00:00Z"))
                .completedAt(Instant.parse("2020-01-02T00:01:00Z")).build());
        MemoryJobStore jobs = new MemoryJobStore();
        StubSemantic semantic = new StubSemantic();
        TraceAnalysisJobService service = service(jobs, telemetry, new CandidateStore(), semantic, new StubVisual());

        TraceAnalysisJobView result = service.startBatch("LLM", "TARGETED", 2, null,
                "admin", true, null, null);

        assertEquals("PARTIAL", result.job().getStatus());
        assertEquals(1, result.job().getSucceededItems());
        assertEquals(1, result.job().getFailedItems());
        TraceAnalysisItem failed = result.items().stream().filter(item -> "FAILED".equals(item.getStatus())).findFirst().orElseThrow();
        assertEquals(2, failed.getAttempt());
        assertEquals(0D, failed.getEstimatedCost(), 0.0001D);
        assertEquals(0.01D, result.job().getActualCost(), 0.0001D);
        assertTrue(semantic.calls >= 3);
    }

    @Test
    public void nonRetryableAnalyzerFailureStopsAfterOneAttempt() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-invalid", "SUCCESS", 10L);
        MemoryJobStore jobs = new MemoryJobStore();
        StubSemantic semantic = new StubSemantic();
        TraceAnalysisJobService service = service(jobs, telemetry, new CandidateStore(), semantic, new StubVisual());

        TraceAnalysisJobView result = service.startBatch("LLM", "TARGETED", 1, null,
                "admin", true, null, null);

        assertEquals("FAILED", result.job().getStatus());
        assertEquals(1, result.items().get(0).getAttempt());
        assertEquals("IllegalArgumentException", result.items().get(0).getErrorClass());
        assertEquals(1, semantic.calls);
    }

    @Test
    public void batchSnapshotExcludesTracesCreatedAfterTheSnapshot() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-old", "SUCCESS", 10L);
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-completed-after-snapshot").status("SUCCESS").latencyMs(10L)
                .startedAt(Instant.parse("2020-01-01T00:00:00Z"))
                .completedAt(Instant.parse("2024-01-01T00:00:00Z")).build());
        MemoryJobStore jobs = new MemoryJobStore();
        TraceAnalysisJobService service = service(jobs, telemetry, new CandidateStore(), new StubSemantic(), new StubVisual());

        TraceAnalysisJobView result = service.startBatch("DETERMINISTIC", "TARGETED", 2,
                Instant.parse("2021-01-01T00:00:00Z"), "admin", true, null, null);

        assertEquals(1, result.items().size());
        assertEquals("run-old", result.items().get(0).getSourceRunId());
        assertTrue(result.job().getSampleDefinitionJson().contains("selectionHash"));
    }

    @Test
    public void unavailableAnalyzerDoesNotReportProviderCost() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-good", "SUCCESS", 10L);
        MemoryJobStore jobs = new MemoryJobStore();
        StubSemantic unavailable = new StubSemantic() {
            @Override public AnalysisResult analyzeRun(String run, String actor, boolean confirmed, String ip, String ua) {
                return new AnalysisResult("UNAVAILABLE", "calibration_not_approved", null, 0D);
            }
        };
        TraceAnalysisJobService service = service(jobs, telemetry, new CandidateStore(), unavailable, new StubVisual());

        TraceAnalysisJobView result = service.startBatch("LLM", "TARGETED", 1, null,
                "admin", true, null, null);

        assertEquals("FAILED", result.job().getStatus());
        assertEquals("UNAVAILABLE", result.items().get(0).getOutcomeStatus());
        assertEquals(0D, result.job().getActualCost(), 0.0001D);
    }

    @Test
    public void batchWithoutExplicitSnapshotIsIdempotentWithinTheRequestWindow() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-good", "SUCCESS", 10L);
        MemoryJobStore jobs = new MemoryJobStore();
        TraceAnalysisJobService service = service(jobs, telemetry, new CandidateStore(), new StubSemantic(), new StubVisual());

        TraceAnalysisJobView first = service.startBatch("DETERMINISTIC", "TARGETED", 1, null,
                "admin", true, null, null);
        TraceAnalysisJobView second = service.startBatch("DETERMINISTIC", "TARGETED", 1, null,
                "admin", true, null, null);

        assertEquals(first.job().getId(), second.job().getId());
        assertEquals(1, jobs.jobs.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void batchRejectsRequestsOverTheReservedBudget() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-1", "SUCCESS", 10L);
        TraceAnalysisJobService service = new TraceAnalysisJobService(new MemoryJobStore(), telemetry,
                new DeterministicCandidateSelectorService(telemetry, new CandidateStore()), new StubSemantic(),
                new StubVisual(), Runnable::run, 50, 0.001D);
        service.startBatch("LLM", "TARGETED", 1, null, "admin", true, null, null);
    }

    private TraceAnalysisJobService service(MemoryJobStore jobs, FakeAgentUsageTelemetryStore telemetry,
                                            CandidateStore candidates, StubSemantic semantic, StubVisual visual) {
        return new TraceAnalysisJobService(jobs, telemetry,
                new DeterministicCandidateSelectorService(telemetry, candidates), semantic, visual,
                Runnable::run, 50, 5D);
    }

    private FakeAgentUsageTelemetryStore telemetry(String id, String status, long latencyMs) {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id(id).status(status).latencyMs(latencyMs)
                .startedAt(Instant.parse("2020-01-01T00:00:00Z"))
                .completedAt(Instant.parse("2020-01-01T00:01:00Z")).build());
        return telemetry;
    }

    private static class StubSemantic extends SemanticAnomalyDiscoveryService {
        private int calls;
        private StubSemantic() { super(null, null, null, null, Runnable::run, true, true, "stub", 10, 0.01D, Clock.systemUTC()); }
        @Override public AnalysisResult analyzeRun(String run, String actor, boolean confirmed, String ip, String ua) {
            calls++;
            if (run.contains("timeout")) throw new IllegalStateException("provider timeout");
            if (run.contains("invalid")) throw new IllegalArgumentException("invalid analyzer input");
            return new AnalysisResult("NO_FINDING", null, null, 0.01D);
        }
        @Override public String analyzerVersion() { return "stub"; }
        @Override public double estimatedCostPerAnalysisUsd() { return 0.01D; }
    }

    private static final class StubVisual extends VisualAnomalyDiscoveryService {
        private StubVisual() { super(null, null, null, null, null, true, true, "stub", 1, Clock.systemUTC()); }
        @Override public Result analyzeRun(String run, String actor, boolean confirmed) { return new Result("NO_FINDING", null, null, 0D, 0.02D); }
        @Override public String analyzerVersion() { return "stub"; }
        @Override public double estimatedCostPerAnalysisUsd() { return 0.02D; }
    }

    private static final class MemoryJobStore implements ITraceAnalysisJobStore {
        private final Map<String, TraceAnalysisJob> jobs = new LinkedHashMap<>();
        private final Map<String, List<TraceAnalysisItem>> items = new LinkedHashMap<>();
        @Override public Optional<TraceAnalysisJob> findJob(String id) { return Optional.ofNullable(jobs.get(id)); }
        @Override public Optional<TraceAnalysisJob> findJobByIdempotencyKey(String key) { return jobs.values().stream().filter(job -> key.equals(job.getIdempotencyKey())).findFirst(); }
        @Override public List<TraceAnalysisJob> listJobs(int limit) { return jobs.values().stream().limit(limit).toList(); }
        @Override public List<TraceAnalysisItem> listItems(String jobId) { return items.getOrDefault(jobId, List.of()); }
        @Override public TraceAnalysisJob insertIfAbsent(TraceAnalysisJob job, List<TraceAnalysisItem> values) {
            TraceAnalysisJob existing = jobs.values().stream()
                    .filter(value -> job.getIdempotencyKey().equals(value.getIdempotencyKey())).findFirst().orElse(null);
            if (existing != null) return existing;
            jobs.put(job.getId(), job); items.put(job.getId(), new ArrayList<>(values)); return job;
        }
        @Override public void updateJob(TraceAnalysisJob job) { jobs.put(job.getId(), job); }
        @Override public void updateItem(TraceAnalysisItem item) { }
    }

    private static final class CandidateStore implements ITraceToEvalStore {
        private final List<EvalCaseCandidate> values = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return values.stream().filter(value -> id.equals(value.getId())).findFirst(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return values.stream().filter(value -> run.equals(value.getSourceRunId()) && family.equals(value.getFailureFamily())).findFirst(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { values.add(candidate); }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
}
