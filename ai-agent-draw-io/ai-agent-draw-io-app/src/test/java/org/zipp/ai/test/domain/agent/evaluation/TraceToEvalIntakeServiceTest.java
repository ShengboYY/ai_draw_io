package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalIntakeService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TraceToEvalIntakeServiceTest {
    @Test
    public void shouldReturnTheExistingCandidateForTheSameManualRun() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-1").agentId("drawing-agent").status("SUCCESS").build());
        MemoryStore store = new MemoryStore();
        TraceToEvalIntakeService service = new TraceToEvalIntakeService(telemetry, store);

        EvalCaseCandidate first = service.createManualCandidate("run-1", "reviewer-a");
        EvalCaseCandidate duplicate = service.createManualCandidate("run-1", "reviewer-b");

        assertSame(first, duplicate);
        assertEquals(1, store.candidates.size());
        assertEquals("Manual review of run status=SUCCESS", first.getEvidenceSummary());
    }

    @Test
    public void shouldFilterAndApplyOnlyAllowedQueueTransitions() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-1").status("FAILED").build());
        MemoryStore store = new MemoryStore();
        TraceToEvalIntakeService service = new TraceToEvalIntakeService(telemetry, store);
        EvalCaseCandidate candidate = service.createManualCandidate("run-1", "reviewer-a");

        service.transition(candidate.getId(), "TRIAGED", "reviewer-a", "worth investigating");

        assertEquals(EvalCandidateStatus.TRIAGED, candidate.getStatus());
        assertEquals(1, service.listCandidates("TRIAGED", "high", 50, 0).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void queueTransitionCannotBypassTheApprovalFlow() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-1").status("FAILED").build());
        TraceToEvalIntakeService service = new TraceToEvalIntakeService(telemetry, new MemoryStore());
        EvalCaseCandidate candidate = service.createManualCandidate("run-1", "reviewer-a");

        service.transition(candidate.getId(), "APPROVED", "reviewer-a", "skip review");
    }

    private static class MemoryStore implements ITraceToEvalStore {
        private final Map<String, EvalCaseCandidate> candidates = new HashMap<>();
        public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.ofNullable(candidates.get(id)); }
        public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String runId, String family) { return candidates.values().stream().filter(c -> runId.equals(c.getSourceRunId()) && family.equals(c.getFailureFamily())).findFirst(); }
        public List<EvalCaseCandidate> listCandidates(EvalCandidateStatus status, String risk, int limit, int offset) { return candidates.values().stream().filter(c -> status == null || status == c.getStatus()).filter(c -> risk == null || risk.equals(c.getRisk())).skip(offset).limit(limit).toList(); }
        public void insertCandidate(EvalCaseCandidate candidate) { candidates.put(candidate.getId(), candidate); }
        public void updateCandidateStatus(String id, EvalCandidateStatus status) { candidates.get(id).setStatus(status); }
        public void insertReview(EvalCaseReview review) { }
        public void insertLineage(EvalCaseLineage lineage) { }
    }
}
