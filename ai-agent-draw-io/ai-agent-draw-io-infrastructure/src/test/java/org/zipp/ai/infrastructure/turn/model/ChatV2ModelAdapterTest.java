package org.zipp.ai.infrastructure.turn.model;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.RouterContextView;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntentReady;
import org.zipp.ai.application.turn.classification.SemanticRouterInput;
import org.zipp.ai.application.turn.classification.SourceIntentKind;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatV2ModelAdapterTest {

    @Test
    void modelInvokerRebuildsTheSameEnvelopeOnFreshSessions() {
        RecordingChat chat = new RecordingChat("{}");
        ToolFreeChatModelInvoker invoker = new ToolFreeChatModelInvoker(chat, "300023", "test-router");
        ModelInputBinding binding = binding(ModelInputBinding.digestOf("router-projection"));

        invoker.invoke(binding, "canonical rendered input");
        String firstEnvelope = chat.lastText;
        invoker.invoke(binding, "canonical rendered input");

        assertEquals(2, chat.createSessionCalls);
        assertEquals(2, chat.sessionIds.size());
        assertFalse(chat.sessionIds.get(0).equals(chat.sessionIds.get(1)));
        assertEquals(firstEnvelope, chat.lastText);
        assertTrue(chat.lastText.contains("MODEL_INPUT_SCHEMA_VERSION"));
        assertTrue(chat.lastText.contains("TURN_KEY_CONVERSATION"));
        assertTrue(chat.lastText.contains("CONTEXT_READ_SET_DIGEST"));
        assertTrue(chat.lastText.contains("RENDERED_INPUT_DIGEST"));
    }

    @Test
    void cancelledInvocationStopsBeforeCreatingAModelSession() {
        RecordingChat chat = new RecordingChat("{}");
        ToolFreeChatModelInvoker invoker =
                new ToolFreeChatModelInvoker(chat, "300023", "test-router");

        assertThrows(CancellationException.class, () -> invoker.invoke(
                binding(ModelInputBinding.digestOf("cancelled")),
                "canonical rendered input",
                () -> true));

        assertEquals(0, chat.createSessionCalls);
    }

    @Test
    void providerCancellationIsNotReclassifiedAsModelFailure() {
        RecordingChat chat = new RecordingChat("{}");
        chat.failure = new RuntimeException(new CancellationException("provider cancelled"));
        ToolFreeChatModelInvoker invoker =
                new ToolFreeChatModelInvoker(chat, "300023", "test-router");

        assertThrows(CancellationException.class, () -> invoker.invoke(
                binding(ModelInputBinding.digestOf("cancelled")),
                "canonical rendered input",
                () -> false));
    }

    @Test
    void semanticRouterUsesFreshToolFreeInvocationAndStrictOutput() {
        RecordingChat chat = new RecordingChat(
                "{\"action\":\"CREATE\",\"outputIntent\":\"DRAWING\","
                        + "\"targetNeed\":\"NOT_REQUIRED\",\"diagramType\":\"flowchart\","
                        + "\"skillName\":\"drawio-flowchart\","
                        + "\"sourceIntent\":\"NO_SOURCE\",\"sourceConfidence\":\"HIGH\","
                        + "\"attachmentRefs\":[],\"relevanceQuery\":null,"
                        + "\"sourceReason\":\"plain request\"}");
        ChatSemanticIntentRouterAdapter adapter = new ChatSemanticIntentRouterAdapter(
                new ToolFreeChatModelInvoker(chat, "300023", "test-router"));

        SemanticRouterInput routerInput = new SemanticRouterInput(
                new CurrentInstruction("draw a flowchart"),
                new RouterContextView(true, false, 1, true, true,
                        "three nodes", List.of("previous"), "summary", "chartbook-1",
                        "blue", "", "", List.of(), "", List.of("short labels")));
        routerInput = routerInput.withModelInputBinding(binding(routerInput.inputDigest()));
        SemanticIntentReady ready = assertInstanceOf(SemanticIntentReady.class,
                adapter.route(routerInput));

        assertEquals(SemanticAction.CREATE, ready.intent().action());
        assertEquals(OutputIntent.DRAWING, ready.intent().outputIntent());
        assertEquals(TargetNeed.NOT_REQUIRED, ready.intent().targetNeed());
        assertEquals(SourceIntentKind.NO_SOURCE, ready.intent().sourceIntent().kind());
        assertEquals(1, chat.createSessionCalls);
        assertTrue(chat.lastText.contains("CHARTBOOK_PROFILE_DATA"));
        assertTrue(chat.lastText.contains("ELIGIBLE_ATTACHMENT_CANDIDATES_DATA"));
        assertFalse(chat.lastText.contains("SOURCE_AVAILABILITY"));
        assertFalse(chat.lastText.contains("EVIDENCE_DATA"));
    }

    @Test
    void semanticRouterRejectsAdditionalOutputFields() {
        RecordingChat chat = new RecordingChat(
                "{\"action\":\"CREATE\",\"outputIntent\":\"DRAWING\","
                        + "\"targetNeed\":\"NOT_REQUIRED\",\"diagramType\":\"flowchart\","
                        + "\"skillName\":\"none\","
                        + "\"sourceIntent\":\"NO_SOURCE\",\"sourceConfidence\":\"HIGH\","
                        + "\"attachmentRefs\":[],\"relevanceQuery\":null,"
                        + "\"sourceReason\":\"plain\",\"sourceBody\":\"bad\"}");
        ChatSemanticIntentRouterAdapter adapter = new ChatSemanticIntentRouterAdapter(
                new ToolFreeChatModelInvoker(chat, "300023", "test-router"));

        SemanticRouterInput routerInput = new SemanticRouterInput(
                new CurrentInstruction("draw"),
                new RouterContextView(false, false, 0, false, false));
        routerInput = routerInput.withModelInputBinding(binding(routerInput.inputDigest()));
        assertEquals("V2_SEMANTIC_ROUTER_OUTPUT_INVALID",
                ((org.zipp.ai.application.turn.classification.SemanticIntentUnavailable)
                        adapter.route(routerInput)).code());
    }

    @Test
    void modelInvocationCarriesThePhasedRunContextIntoTheChatCall() {
        RecordingChat chat = new RecordingChat("{}");
        ToolFreeChatModelInvoker invoker =
                new ToolFreeChatModelInvoker(chat, "300023", "v2-plain-generation");
        AgentUsageTelemetryContext.RunContext run = new AgentUsageTelemetryContext.RunContext(
                "run-1", "request-1", "diagram-1", "owner-1", "300023", "chat",
                "PLATFORM", null, "openai", "unknown", "turn_v2_execution");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run)) {
            invoker.invoke(binding(ModelInputBinding.digestOf("phased")), "canonical rendered input");
        }

        // Without the ambient run reaching the chat call, the model span has no run to attach to.
        assertNotNull(chat.observedContext);
        assertEquals("run-1", chat.observedContext.runId());
        assertEquals("v2-plain-generation", chat.observedContext.phase());
        assertTrue(AgentUsageTelemetryContext.current().isEmpty());
    }

    @Test
    void modelAdapterCannotBeConstructedAroundToolEnabledAgent() {
        RecordingChat chat = new RecordingChat("{}");
        chat.toolFree = false;

        assertThrows(IllegalArgumentException.class,
                () -> new ToolFreeChatModelInvoker(chat, "unsafe", "test"));
    }

    private static final class RecordingChat implements IChatService {
        private final String response;
        private String lastText;
        private int createSessionCalls;
        private final List<String> sessionIds = new java.util.ArrayList<>();
        private boolean toolFree = true;
        private RuntimeException failure;
        private AgentUsageTelemetryContext.RunContext observedContext;

        private RecordingChat(String response) {
            this.response = response;
        }

        @Override
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
            return List.of();
        }

        @Override
        public boolean isAgentToolFree(String agentId) {
            return toolFree;
        }

        @Override
        public String createSession(String agentId, String userId) {
            createSessionCalls++;
            String sessionId = "fresh-session-" + createSessionCalls;
            sessionIds.add(sessionId);
            return sessionId;
        }

        @Override
        public String ensureSession(String agentId, String userId, String sessionId) {
            return sessionId;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String message) {
            return List.of(response);
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            return List.of(response);
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            return Flowable.empty();
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity command) {
            observedContext = AgentUsageTelemetryContext.current().orElse(null);
            if (failure != null) throw failure;
            lastText = command.getTexts().get(0).getMessage();
            return List.of(response);
        }
    }

    private static ModelInputBinding binding(String inputDigest) {
        return ModelInputBinding.bound(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "a".repeat(64), inputDigest);
    }
}
