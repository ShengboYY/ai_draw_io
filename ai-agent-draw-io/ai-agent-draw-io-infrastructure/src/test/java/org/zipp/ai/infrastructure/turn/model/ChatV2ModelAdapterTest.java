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
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterUnavailable;
import org.zipp.ai.application.turn.demand.SourceDemandProposalReady;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void semanticRouterUsesFreshToolFreeInvocationAndStrictOutput() {
        RecordingChat chat = new RecordingChat(
                "{\"action\":\"CREATE\",\"outputIntent\":\"DRAWING\","
                        + "\"targetNeed\":\"NOT_REQUIRED\",\"diagramType\":\"flowchart\","
                        + "\"skillName\":\"drawio-flowchart\"}");
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
        assertEquals(1, chat.createSessionCalls);
        assertTrue(chat.lastText.contains("CHARTBOOK_PROFILE_DATA"));
        assertFalse(chat.lastText.contains("SOURCE_AVAILABILITY"));
        assertFalse(chat.lastText.contains("EVIDENCE_DATA"));
    }

    @Test
    void semanticRouterRejectsAdditionalOutputFields() {
        RecordingChat chat = new RecordingChat(
                "{\"action\":\"CREATE\",\"outputIntent\":\"DRAWING\","
                        + "\"targetNeed\":\"NOT_REQUIRED\",\"diagramType\":\"flowchart\","
                        + "\"skillName\":\"none\",\"sourceUse\":\"NONE\"}");
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
    void demandInterpreterReceivesOnlyRestrictedInputAndReturnsProposal() {
        CurrentInstruction instruction = new CurrentInstruction("use the attached specification");
        RestrictedSourceDemandInput demandInput = new RestrictedSourceDemandInput(
                instruction,
                List.of(new org.zipp.ai.application.turn.OpaqueConversationFileRef("file-1")),
                Optional.of("chartbook-1"), Set.of("first"));
        demandInput = demandInput.withModelInputBinding(binding(demandInput.inputDigest()));
        RecordingChat chat = new RecordingChat(
                "{\"demandKind\":\"CURRENT_MESSAGE_ATTACHMENTS_REQUIRED\","
                        + "\"confidence\":\"HIGH\",\"safeReason\":\"use the attached file\","
                        + "\"attachmentRefs\":[\"file-1\"],\"relevanceQuery\":null,"
                        + "\"inputDigest\":\"" + demandInput.inputDigest() + "\","
                        + "\"modelVersion\":\"m2-demand-model\",\"policyVersion\":\"m2-demand-policy\","
                        + "\"spans\":[{\"start\":0,\"end\":25,\"digest\":\""
                        + instruction.spanDigest(0, 25) + "\"}]}");
        ChatSourceDemandInterpreterAdapter adapter = new ChatSourceDemandInterpreterAdapter(
                new ToolFreeChatModelInvoker(chat, "300024", "test-demand"));

        SourceDemandProposalReady ready = assertInstanceOf(SourceDemandProposalReady.class,
                adapter.interpret(new RestrictedSourceDemandInput(
                        instruction,
                        List.of(new org.zipp.ai.application.turn.OpaqueConversationFileRef("file-1")),
                        Optional.of("chartbook-1"), Set.of("first"))
                        .withModelInputBinding(binding(demandInput.inputDigest()))));

        assertEquals("file-1", ((org.zipp.ai.application.turn.demand.TypedSourceDemandProposal)
                ready.proposal()).attachmentRefs().get(0));
        assertTrue(chat.lastText.contains("CURRENT_MESSAGE_ATTACHMENT_REFS_DATA"));
        assertTrue(chat.lastText.contains("chartbook-1"));
        assertFalse(chat.lastText.contains("CONVERSATION_DATA"));
        assertFalse(chat.lastText.contains("PROFILE_DATA"));
        assertFalse(chat.lastText.contains("SOURCE_BODY"));
        assertTrue(chat.lastText.contains("INPUT_DIGEST_DATA"));
        assertTrue(chat.lastText.contains(demandInput.inputDigest()));
    }

    @Test
    void demandInterpreterRejectsUnknownOutputFields() {
        RecordingChat chat = new RecordingChat(
                "{\"demandKind\":\"NO_SOURCE\",\"confidence\":\"HIGH\","
                        + "\"safeReason\":\"plain\",\"attachmentRefs\":[],"
                        + "\"relevanceQuery\":null,\"spans\":[],\"sourceBody\":\"bad\"}");
        ChatSourceDemandInterpreterAdapter adapter = new ChatSourceDemandInterpreterAdapter(
                new ToolFreeChatModelInvoker(chat, "300024", "test-demand"));

        RestrictedSourceDemandInput input = new RestrictedSourceDemandInput(
                new CurrentInstruction("draw"), List.of(), Optional.empty(), Set.of());
        SourceDemandInterpreterUnavailable unavailable = assertInstanceOf(
                SourceDemandInterpreterUnavailable.class,
                adapter.interpret(input.withModelInputBinding(binding(input.inputDigest()))));
        assertEquals("V2_SOURCE_DEMAND_OUTPUT_INVALID", unavailable.code());
    }

    @Test
    void demandInterpreterRejectsProposalBoundToAnotherInput() {
        CurrentInstruction instruction = new CurrentInstruction("draw a flow");
        RestrictedSourceDemandInput input = new RestrictedSourceDemandInput(
                instruction, List.of(), Optional.empty(), Set.of());
        input = input.withModelInputBinding(binding(input.inputDigest()));
        RecordingChat chat = new RecordingChat(
                "{\"demandKind\":\"NO_SOURCE\",\"confidence\":\"HIGH\","
                        + "\"safeReason\":\"plain\",\"attachmentRefs\":[],"
                        + "\"relevanceQuery\":null,\"inputDigest\":\"" + "0".repeat(64) + "\","
                        + "\"modelVersion\":\"m2-demand-model\",\"policyVersion\":\"m2-demand-policy\","
                        + "\"spans\":[{\"start\":0,\"end\":" + instruction.value().length()
                        + ",\"digest\":\""
                        + instruction.spanDigest(0, instruction.value().length()) + "\"}]}");
        ChatSourceDemandInterpreterAdapter adapter = new ChatSourceDemandInterpreterAdapter(
                new ToolFreeChatModelInvoker(chat, "300024", "test-demand"));

        SourceDemandInterpreterUnavailable unavailable = assertInstanceOf(
                SourceDemandInterpreterUnavailable.class, adapter.interpret(input));
        assertEquals("V2_SOURCE_DEMAND_OUTPUT_INVALID", unavailable.code());
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
