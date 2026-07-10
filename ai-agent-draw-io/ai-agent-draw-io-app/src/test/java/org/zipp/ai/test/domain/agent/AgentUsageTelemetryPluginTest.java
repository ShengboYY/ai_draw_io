package org.zipp.ai.test.domain.agent;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import org.junit.After;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.service.armory.matter.plugin.AgentUsageTelemetryPlugin;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentUsageTelemetryPluginTest {

    private String invocationId;
    private AgentUsageTelemetryContext.InvocationState invocationState;

    @After
    public void clearInvocation() {
        AgentUsageTelemetryContext.clearInvocation(invocationId);
        if (invocationState != null) {
            invocationState.close();
        }
    }

    @Test
    public void modelCallbacksPersistLinkedInputAndOutputPayloads() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_1", null, null);
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        inject(plugin, "agentDebugTraceService", debugService);
        CallbackContext callback = callbackContext("inv_1", "drawing_agent");
        registerInvocation("inv_1");
        LlmRequest.Builder request = LlmRequest.builder()
                .model("gemini-test")
                .contents(List.of(Content.builder()
                        .role("user")
                        .parts(List.of(Part.fromText("draw a sequence diagram")))
                        .build()));
        LlmResponse response = LlmResponse.builder()
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText("diagram ready")))
                        .build())
                .modelVersion("gemini-test-001")
                .build();

        plugin.beforeModelCallback(callback, request);
        plugin.afterModelCallback(callback, response);

        assertEquals(1, telemetryStore.llmCalls.size());
        org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry call = telemetryStore.llmCalls.get(0);
        String spanId = call.getId();
        assertTrue(spanId.startsWith("alc_"));
        assertEquals("google", call.getProvider());
        assertTrue(call.getTtftMs() != null && call.getTtftMs() >= 0);
        assertEquals(Integer.valueOf(1), call.getAttemptCount());
        assertEquals(Integer.valueOf(0), call.getRetryCount());
        assertEquals(2, debugStore.captures.size());
        assertEquals(spanId, debugStore.captures.get(0).getSpanId());
        assertEquals("INPUT", debugStore.captures.get(0).getPayloadKind());
        assertTrue(debugStore.captures.get(0).getContent().contains("draw a sequence diagram"));
        assertEquals(spanId, debugStore.captures.get(1).getSpanId());
        assertEquals("OUTPUT", debugStore.captures.get(1).getPayloadKind());
        assertTrue(debugStore.captures.get(1).getContent().contains("diagram ready"));
    }

    @Test
    public void toolCallbacksPersistLinkedArgumentsAndResultPayloads() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_1", null, null);
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        inject(plugin, "agentDebugTraceService", debugService);
        InvocationContext invocation = invocationContext("inv_2", "drawing_agent");
        ToolContext toolContext = ToolContext.builder(invocation)
                .actions(new EventActions())
                .functionCallId("function_1")
                .build();
        BaseTool tool = new BaseTool("lookup_diagram", "test tool") { };
        registerInvocation(toolContext.invocationId());
        assertTrue(AgentUsageTelemetryContext.resolveInvocation(toolContext.invocationId()).isPresent());

        plugin.beforeToolCallback(tool, Map.of("diagramId", "dia_1"), toolContext);
        assertEquals(1, debugStore.captures.size());
        plugin.afterToolCallback(tool, Map.of("diagramId", "dia_1"), toolContext, Map.of("result", "found"));

        assertEquals(1, telemetryStore.toolCalls.size());
        String spanId = telemetryStore.toolCalls.get(0).getId();
        assertTrue(spanId.startsWith("atc_"));
        assertEquals(2, debugStore.captures.size());
        assertEquals(spanId, debugStore.captures.get(0).getSpanId());
        assertEquals("TOOL_ARGS", debugStore.captures.get(0).getPayloadKind());
        assertTrue(debugStore.captures.get(0).getContent().contains("dia_1"));
        assertEquals(spanId, debugStore.captures.get(1).getSpanId());
        assertEquals("TOOL_RESULT", debugStore.captures.get(1).getPayloadKind());
        assertTrue(debugStore.captures.get(1).getContent().contains("found"));
    }

    @Test
    public void debugCaptureFailureDoesNotAbortModelTelemetry() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_1", null, null);
        debugStore.failCaptures = true;
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        inject(plugin, "agentDebugTraceService", debugService);
        CallbackContext callback = callbackContext("inv_3", "drawing_agent");
        registerInvocation("inv_3");

        plugin.beforeModelCallback(callback, LlmRequest.builder().model("gemini-test"));
        plugin.afterModelCallback(callback, LlmResponse.builder().modelVersion("gemini-test-001").build());

        assertEquals(1, telemetryStore.llmCalls.size());
    }

    @Test
    public void streamedModelCallbacksWaitForFinalUsageAndAggregateRawChunks() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_1", null, null);
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        inject(plugin, "agentDebugTraceService", debugService);
        CallbackContext callback = callbackContext("inv_stream", "drawing_agent");
        registerInvocation("inv_stream");
        LlmResponse first = LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText("hello "))).build())
                .partial(true)
                .build();
        LlmResponse last = LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText("world"))).build())
                .partial(false)
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(12)
                        .candidatesTokenCount(4)
                        .totalTokenCount(16)
                        .cachedContentTokenCount(3)
                        .build())
                .build();

        plugin.beforeModelCallback(callback, LlmRequest.builder().model("gemini-test"));
        plugin.afterModelCallback(callback, first);
        assertEquals(0, telemetryStore.llmCalls.size());
        plugin.afterModelCallback(callback, last);

        assertEquals(1, telemetryStore.llmCalls.size());
        assertEquals(Integer.valueOf(16), telemetryStore.llmCalls.get(0).getTotalTokens());
        String output = debugStore.captures.get(1).getContent();
        assertTrue(output.contains("hello "));
        assertTrue(output.contains("world"));
        assertTrue(output.contains("cachedContentTokenCount"));
    }

    @Test
    public void streamedModelCaptureBoundsRawChunksAndPreservesCombinedText() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_1", null, null);
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        inject(plugin, "agentDebugTraceService", debugService);
        CallbackContext callback = callbackContext("inv_bounded", "drawing_agent");
        registerInvocation("inv_bounded");

        plugin.beforeModelCallback(callback, LlmRequest.builder().model("gemini-test"));
        for (int index = 0; index < 70; index++) {
            plugin.afterModelCallback(callback, LlmResponse.builder()
                    .content(Content.builder().role("model")
                            .parts(List.of(Part.fromText("part-" + index + " "))).build())
                    .partial(true)
                    .build());
        }
        plugin.afterModelCallback(callback, LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText("done"))).build())
                .partial(false)
                .build());

        String output = debugStore.captures.get(1).getContent();
        assertTrue(output.contains("\"droppedChunkCount\":7"));
        assertTrue(output.contains("part-0"));
        assertTrue(output.contains("done"));
    }

    @Test
    public void afterRunDropsUnfinishedModelCallbacks() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryPlugin plugin = new AgentUsageTelemetryPlugin();
        inject(plugin, "agentUsageTelemetryService", new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        InvocationContext invocation = invocationContext("inv_unfinished", "drawing_agent");
        CallbackContext callback = new CallbackContext(invocation, new EventActions());
        registerInvocation("inv_unfinished");

        plugin.beforeModelCallback(callback, LlmRequest.builder().model("gemini-test"));
        plugin.afterRunCallback(invocation);
        plugin.afterModelCallback(callback, LlmResponse.builder().partial(false).build());

        assertEquals(0, telemetryStore.llmCalls.size());
    }

    private CallbackContext callbackContext(String id, String agentName) {
        return new CallbackContext(invocationContext(id, agentName), new EventActions());
    }

    private InvocationContext invocationContext(String id, String agentName) {
        LlmAgent agent = LlmAgent.builder().name(agentName).model("gemini-test").build();
        Session session = Session.builder("sess_1")
                .appName("test")
                .userId("usr_1")
                .build();
        return InvocationContext.builder()
                .invocationId(id)
                .agent(agent)
                .session(session)
                .runConfig(RunConfig.builder().build())
                .build();
    }

    private void registerInvocation(String id) {
        invocationId = id;
        AgentUsageTelemetryContext.RunContext context = new AgentUsageTelemetryContext.RunContext(
                "aru_1", "req_1", "dia_1", "usr_1", "drawio", "chat_stream",
                "PLATFORM", null, "google", "gemini-test", "drawing");
        invocationState = AgentUsageTelemetryContext.newInvocationState(context);
        AgentUsageTelemetryContext.registerInvocation(id, invocationState.stateDelta());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceControl> controls = new ArrayList<>();
        private final List<DebugTraceCapture> captures = new ArrayList<>();
        private boolean failCaptures;

        @Override public void insertControl(DebugTraceControl control) { controls.add(control); }
        @Override public List<DebugTraceControl> listEnabledControls() { return controls; }
        @Override public void insertCapture(DebugTraceCapture capture) {
            if (failCaptures) throw new IllegalStateException("capture unavailable");
            captures.add(capture);
        }
        @Override public int deleteExpiredContent(Instant now) { return 0; }
        @Override public int extendRunContentExpiry(String runId, Instant expiresAt) { return 0; }
    }
}
