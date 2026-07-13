package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRunStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticSamplingPolicy;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.SemanticAnomalyDiscoveryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class SemanticAnomalyDiscoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T08:00:00Z");

    @Test
    public void shouldDiscoverFalseSuccessFromSanitizedProjectionAndDedupe() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("aru_private_1", "SUCCESS");
        MemoryStore store = new MemoryStore();
        RecordingMiner miner = new RecordingMiner(new ISemanticAnomalyMiner.Finding(true, 0.94D,
                "FALSE_SUCCESS", List.of("Run succeeded but the assistant reported that loading failed"),
                "high", true));
        SemanticAnomalyDiscoveryService service = service(telemetry, store, miner,
                "Assistant: 无法加载. Contact alice@example.com, token=sk-secret12345, run=aru_private_1");

        SemanticMinerRun first = service.start(SemanticSamplingPolicy.TARGETED, 10,
                "admin-1", true, "127.0.0.1", "test");
        service.start(SemanticSamplingPolicy.TARGETED, 10, "admin-1", true, "127.0.0.1", "test");

        assertEquals(SemanticMinerRunStatus.COMPLETED, first.getStatus());
        assertEquals(1, store.candidates.size());
        EvalCaseCandidate candidate = store.candidates.get(0);
        assertEquals("MODEL_DETECTED", candidate.getDetectionSource());
        assertEquals("semantic_false_success", candidate.getFailureFamily());
        assertEquals(Double.valueOf(0.94D), candidate.getModelConfidence());
        assertTrue(candidate.getModelEvidence().get(0).contains("loading failed"));
        assertFalse(miner.projection.contains("aru_private_1"));
        assertFalse(miner.projection.contains("alice@example.com"));
        assertFalse(miner.projection.contains("sk-secret"));
        assertTrue(miner.projection.contains("[EMAIL]"));
    }

    @Test
    public void shouldIsolateModelFailureInsideTheAsyncJob() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-1", "SUCCESS");
        MemoryStore store = new MemoryStore();
        ISemanticAnomalyMiner failing = new ISemanticAnomalyMiner() {
            @Override public Finding analyze(String projection) { throw new IllegalStateException("provider timeout"); }
            @Override public String version() { return "semantic-model-v1"; }
        };
        SemanticAnomalyDiscoveryService service = service(telemetry, store, failing, "Assistant reply is available");

        SemanticMinerRun run = service.start(SemanticSamplingPolicy.RANDOM, 5,
                "admin-1", true, "127.0.0.1", "test");

        assertEquals(SemanticMinerRunStatus.COMPLETED_WITH_ERRORS, run.getStatus());
        assertEquals(Integer.valueOf(1), run.getErrorCount());
        assertTrue(store.candidates.isEmpty());
    }

    @Test
    public void shouldFailClosedWhenCalibrationIsNotApproved() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-1", "SUCCESS");
        MemoryStore store = new MemoryStore();
        RecordingMiner miner = new RecordingMiner(null);
        SemanticAnomalyDiscoveryService service = new SemanticAnomalyDiscoveryService(telemetry, store,
                debugService("safe"), miner, Runnable::run, true, false,
                "semantic-model-v1", 50, 0.002D, Clock.fixed(NOW, ZoneOffset.UTC));

        SemanticMinerRun run = service.start(SemanticSamplingPolicy.TARGETED, 10,
                "admin-1", true, "127.0.0.1", "test");

        assertEquals(SemanticMinerRunStatus.UNAVAILABLE, run.getStatus());
        assertEquals("calibration_not_approved", run.getAvailabilityReason());
        assertNull(miner.projection);
    }

    @Test
    public void shouldRejectModelEvidenceThatReintroducesAProductionIdentifier() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-1", "SUCCESS");
        MemoryStore store = new MemoryStore();
        RecordingMiner miner = new RecordingMiner(new ISemanticAnomalyMiner.Finding(true, 0.95D,
                "FALSE_SUCCESS", List.of("production run aru_private_1 could not load"), "high", true));
        SemanticAnomalyDiscoveryService service = service(telemetry, store, miner, "safe assistant reply");

        SemanticMinerRun run = service.start(SemanticSamplingPolicy.TARGETED, 10,
                "admin-1", true, "127.0.0.1", "test");

        assertEquals(SemanticMinerRunStatus.COMPLETED_WITH_ERRORS, run.getStatus());
        assertTrue(store.candidates.isEmpty());
    }

    @Test
    public void lowerConfidenceFindingMustNotEnterTheHighRiskQueue() {
        FakeAgentUsageTelemetryStore telemetry = telemetry("run-1", "SUCCESS");
        MemoryStore store = new MemoryStore();
        RecordingMiner miner = new RecordingMiner(new ISemanticAnomalyMiner.Finding(true, 0.60D,
                "TRAJECTORY_WASTE", List.of("Repeated clarification without progress"), "critical", true));
        SemanticAnomalyDiscoveryService service = service(telemetry, store, miner, "safe assistant reply");

        service.start(SemanticSamplingPolicy.TARGETED, 10, "admin-1", true, "127.0.0.1", "test");

        assertEquals(1, store.candidates.size());
        assertEquals("medium", store.candidates.get(0).getRisk());
    }

    private SemanticAnomalyDiscoveryService service(FakeAgentUsageTelemetryStore telemetry, MemoryStore store,
                                                     ISemanticAnomalyMiner miner, String content) {
        return new SemanticAnomalyDiscoveryService(telemetry, store, debugService(content), miner,
                Runnable::run, true, true, "semantic-model-v1", 50, 0.002D,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private FakeAgentUsageTelemetryStore telemetry(String runId, String status) {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id(runId).userId("user-private")
                .requestId("request-private").diagramId("diagram-private").sessionId("session-private")
                .agentId("drawing-agent").requestType("edit_existing").status(status).latencyMs(42_000L)
                .stepCount(3L).llmCallCount(2L).toolCallCount(1L).build());
        return telemetry;
    }

    private AgentDebugTraceService debugService(String content) {
        return new AgentDebugTraceService(null, null) {
            @Override public List<DebugTraceCapture> viewCapturesForRun(String actor, String run, String ip, String ua) {
                return List.of(DebugTraceCapture.builder().content(content)
                        .contentExpiresAt(NOW.plusSeconds(60)).build());
            }
        };
    }

    private static final class RecordingMiner implements ISemanticAnomalyMiner {
        private final Finding finding;
        private String projection;
        private RecordingMiner(Finding finding) { this.finding = finding; }
        @Override public Finding analyze(String projection) { this.projection = projection; return finding; }
        @Override public String version() { return "semantic-model-v1"; }
    }

    private static final class MemoryStore implements ITraceToEvalStore {
        private final List<EvalCaseCandidate> candidates = new ArrayList<>();
        private final List<SemanticMinerRun> runs = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return candidates.stream().filter(c -> id.equals(c.getId())).findFirst(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return candidates.stream().filter(c -> run.equals(c.getSourceRunId()) && family.equals(c.getFailureFamily())).findFirst(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { candidates.add(candidate); }
        @Override public void mergeCandidateModelEvidence(String id, String version, Double confidence, List<String> evidence, String summary) {
            EvalCaseCandidate candidate = findCandidate(id).orElseThrow();
            candidate.setModelVersion(version); candidate.setModelConfidence(confidence);
            candidate.setModelEvidence(evidence); candidate.setEvidenceSummary(summary);
        }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { findCandidate(id).orElseThrow().setStatus(status); }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
        @Override public void insertSemanticMinerRun(SemanticMinerRun run) { runs.add(run); }
        @Override public void updateSemanticMinerRun(SemanticMinerRun run) { }
    }
}
