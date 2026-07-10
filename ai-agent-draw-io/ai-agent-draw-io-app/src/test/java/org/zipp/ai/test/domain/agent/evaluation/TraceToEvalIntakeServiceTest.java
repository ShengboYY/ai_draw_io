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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TraceToEvalIntakeServiceTest {
    @Test
    public void shouldKeepSourceRunOnlyInCandidateAndNotPublishedLineage() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-1").agentId("drawing-agent").status("FAILED").build());
        telemetry.steps.add(AgentRunStepTelemetry.builder().id("step-1").runId("run-1").phase("drawing").status("FAILED").completedAt(Instant.now()).build());
        MemoryStore store = new MemoryStore();
        TraceToEvalIntakeService service = new TraceToEvalIntakeService(telemetry, store);

        EvalCaseCandidate candidate = service.createManualCandidate("run-1", "reviewer-a");
        assertEquals("run-1", candidate.getSourceRunId());
        service.review(candidate.getId(), "APPROVE", "reviewer-a", "synthetic reconstruction ready");
        EvalCaseLineage lineage = service.recordPublication(candidate.getId(), "draw-edit-001", "core-v1", "manual-synthesis", "reviewer-a");

        assertEquals("trace-derived-synthetic", lineage.getOrigin());
        assertFalse(java.util.Arrays.stream(EvalCaseLineage.class.getDeclaredFields())
                .anyMatch(field -> field.getName().contains("sourceRun") || field.getName().contains("candidate")));
    }

    private static class MemoryStore implements ITraceToEvalStore {
        private final Map<String, EvalCaseCandidate> candidates = new HashMap<>();
        public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.ofNullable(candidates.get(id)); }
        public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String runId, String family) { return candidates.values().stream().filter(c -> runId.equals(c.getSourceRunId()) && family.equals(c.getFailureFamily())).findFirst(); }
        public void insertCandidate(EvalCaseCandidate candidate) { candidates.put(candidate.getId(), candidate); }
        public void updateCandidateStatus(String id, EvalCandidateStatus status) { candidates.get(id).setStatus(status); }
        public void insertReview(EvalCaseReview review) { }
        public void insertLineage(EvalCaseLineage lineage) { }
    }
}
