package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.trigger.evaluation.ProductionLiveEvalAdapter;
import org.zipp.ai.trigger.http.service.AgentConversationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

import static org.junit.Assert.*;

public class ProductionLiveEvalAdapterTest {

    @Test
    public void mutationMustUseThePersistedCanvasInsteadOfAssistantText() throws Exception {
        MemoryCanvasStore canvases = new MemoryCanvasStore();
        AgentRunDetail detail = AgentRunDetail.builder()
                .run(AgentRunTelemetry.builder().id("live-run-1").status("SUCCESS").build())
                .traceEvents(List.of(AgentTraceEvent.builder().eventType("ROUTING_DECIDED")
                        .metadataJson("{\"routeType\":\"edit_existing\",\"diagramType\":\"architecture\"}").build()))
                .llmCalls(List.of(LlmCallTelemetry.builder().model("gpt-5.5").build()))
                .toolCalls(List.of(ToolCallTelemetry.builder().toolName("modify_diagram").status("SUCCESS").build()))
                .build();
        IAgentUsageTelemetryStore telemetry = new FixedTelemetryStore(detail);

        String finalXml = canvas("Gateway");
        AgentConversationService conversation = new AgentConversationService() {
            @Override public void stream(ChatRequestDTO request, ResponseBodyEmitter emitter) {
                try {
                    CanvasState initial = canvases.find(request.getUserId(), request.getDiagramId()).orElseThrow();
                    assertEquals(canvas("API"), initial.getCurrentXml());
                    assertEquals(initial.getVersion(), request.getExpectedVersion());
                    canvases.save(CanvasState.builder().userId(request.getUserId()).diagramId(request.getDiagramId())
                            .version(request.getExpectedVersion()).currentXml(finalXml).build());
                    emitter.send("{\"phase\":\"thinking\",\"chunk\":{\"type\":\"meta\",\"runId\":\"live-run-1\"}}\n");
                    emitter.send("{\"phase\":\"done\",\"chunk\":{\"type\":\"user\",\"content\":\"Diagram updated\"}}\n");
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        };

        EvalExecution execution = new ProductionLiveEvalAdapter(
                conversation, telemetry, canvases, "agent", "sha", 1_000L).execute(editCase());

        assertEquals(finalXml, execution.getFinalCanvasXml());
        assertEquals("Diagram updated", execution.getResponseText());
        assertEquals(EvalTrace.TaskOutcome.FULFILLED, execution.getTrace().getTaskOutcome());
        assertNotEquals(execution.getTrace().getBeforeCanvasHash(), execution.getTrace().getAfterCanvasHash());
        assertTrue(execution.getTrace().getBeforeCanvasHash().startsWith("sha256:"));
        assertTrue(canvases.deleted);
    }

    private EvalCaseDefinition editCase() {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRequireCanvasChange(true);
        expected.setTaskOutcome(EvalTrace.TaskOutcome.FULFILLED);
        EvalCaseDefinition.Replay replay = new EvalCaseDefinition.Replay();
        replay.setInitialCanvasXml(canvas("API"));
        EvalCaseDefinition.ExecutionProfile profile = EvalCaseDefinition.ExecutionProfile.builder().model("gpt-5.5").build();
        return EvalCaseDefinition.builder().caseId("live-edit").input(Map.of("user", "Rename API to Gateway"))
                .expected(expected).replay(replay).executionProfile(profile).build();
    }

    private String canvas(String label) {
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='" + label + "' vertex='1' parent='1'/></root></mxGraphModel>";
    }

    private static final class MemoryCanvasStore implements ICanvasStateStore {
        private final Map<String, CanvasState> values = new HashMap<>();
        private boolean deleted;

        @Override public Optional<CanvasState> find(String userId, String diagramId) {
            return Optional.ofNullable(values.get(userId + ":" + diagramId));
        }

        @Override public CanvasState save(CanvasState state) {
            String key = state.getUserId() + ":" + state.getDiagramId();
            CanvasState current = values.get(key);
            long version = current == null ? 1L : current.getVersion() + 1L;
            CanvasState saved = CanvasState.builder().userId(state.getUserId()).diagramId(state.getDiagramId())
                    .title(state.getTitle()).currentXml(state.getCurrentXml()).version(version).build();
            values.put(key, saved);
            return saved;
        }

        @Override public boolean softDelete(String userId, String diagramId) {
            deleted = values.remove(userId + ":" + diagramId) != null;
            return deleted;
        }
    }

    private static final class FixedTelemetryStore implements IAgentUsageTelemetryStore {
        private final AgentRunDetail detail;
        private FixedTelemetryStore(AgentRunDetail detail) { this.detail = detail; }
        @Override public void insertRun(AgentRunTelemetry run) { }
        @Override public void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs) { }
        @Override public void insertStep(AgentRunStepTelemetry step) { }
        @Override public void insertLlmCall(LlmCallTelemetry call) { }
        @Override public void insertToolCall(ToolCallTelemetry call) { }
        @Override public AgentUsageSummary summarizeForUser(String userId) { return null; }
        @Override public AdminUsageSummary summarizeGlobal() { return null; }
        @Override public List<UsageDimensionSummary> summarizeByProviderModelCredentialSource() { return List.of(); }
        @Override public List<AgentRunTelemetry> listTerminalRunsBetween(
                Instant completedFrom, Instant completedTo, int limit) { return List.of(); }
        @Override public Optional<AgentRunDetail> findRunDetail(String runId) {
            return "live-run-1".equals(runId) ? Optional.of(detail) : Optional.empty();
        }
    }
}
