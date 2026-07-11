package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.service.evaluation.intake.DeterministicCandidateSelectorService;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeterministicCandidateSelectorServiceTest {

    @Test
    public void shouldGroupDeterministicSignalsAndRemainIdempotent() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-1").agentId("drawing-agent").status("FAILED").build());
        telemetry.toolCalls.add(ToolCallTelemetry.builder().id("tool-1").runId("run-1").phase("drawing")
                .toolName("modify_diagram").status("FAILED").errorClass("InvalidXml").build());
        telemetry.traceEvents.add(AgentTraceEvent.builder().id("route").runId("run-1").eventType("ROUTING_DECIDED")
                .phase("routing").status("SUCCESS").metadataJson("{\"routeType\":\"edit_existing\"}").build());
        telemetry.traceEvents.add(AgentTraceEvent.builder().id("repair-1").runId("run-1").eventType("DRAWING_MUTATION")
                .phase("drawing").status("SUCCESS")
                .metadataJson("{\"round\":3,\"retryCount\":2,\"outcome\":\"NEEDS_REPAIR\"}").build());
        telemetry.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder().runId("run-1").version(1L).canvasHash("same").build());
        telemetry.diagramSnapshots.add(AgentDiagramTraceSnapshot.builder().runId("run-1").version(2L).canvasHash("same").build());
        MemoryStore store = new MemoryStore();
        DeterministicCandidateSelectorService selector = new DeterministicCandidateSelectorService(telemetry, store);

        List<EvalCaseCandidate> first = selector.discover(50);
        List<EvalCaseCandidate> second = selector.discover(50);

        assertEquals(3, first.size());
        assertEquals(3, second.size());
        assertEquals(3, store.candidates.size());
        assertTrue(first.stream().anyMatch(candidate -> "execution".equals(candidate.getFailureFamily())));
        assertTrue(first.stream().anyMatch(candidate -> "artifact".equals(candidate.getFailureFamily())
                && candidate.getRuleId().contains("mutation_failed")
                && candidate.getRuleId().contains("mutating_route_canvas_unchanged")));
        assertTrue(first.stream().anyMatch(candidate -> "layout".equals(candidate.getFailureFamily())
                && candidate.getRuleId().contains("repair_budget_exceeded")
                && candidate.getRuleId().contains("blocking_quality_residual")));
    }

    @Test
    public void shouldWaitForTerminalRunTelemetry() {
        FakeAgentUsageTelemetryStore telemetry = new FakeAgentUsageTelemetryStore();
        telemetry.runs.add(AgentRunTelemetry.builder().id("run-live").status("RUNNING").build());
        telemetry.toolCalls.add(ToolCallTelemetry.builder().id("tool-live").runId("run-live")
                .toolName("modify_diagram").status("FAILED").build());

        List<EvalCaseCandidate> selected = new DeterministicCandidateSelectorService(telemetry, new MemoryStore()).discover(50);

        assertTrue(selected.isEmpty());
    }

    private static final class MemoryStore implements ITraceToEvalStore {
        private final List<EvalCaseCandidate> candidates = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return candidates.stream().filter(c -> id.equals(c.getId())).findFirst(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String runId, String family) { return candidates.stream().filter(c -> runId.equals(c.getSourceRunId()) && family.equals(c.getFailureFamily())).findFirst(); }
        @Override public List<EvalCaseCandidate> listCandidates(EvalCandidateStatus status, String risk, int limit, int offset) { return candidates.stream().filter(c -> status == null || status == c.getStatus()).filter(c -> risk == null || risk.equals(c.getRisk())).skip(offset).limit(limit).toList(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { candidates.add(candidate); }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { findCandidate(id).orElseThrow().setStatus(status); }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
}
