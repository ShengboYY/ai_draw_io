package org.zipp.ai.infrastructure.turn.model;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.PlainGenerationResult;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ChartbookMembershipContext;
import org.zipp.ai.application.turn.context.ChartbookProfileContext;
import org.zipp.ai.application.turn.context.ConfirmedMemoryContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentView;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatPlainGenerationAdapterTest {

    private static final String CANVAS_XML = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="node-1" value="Login" vertex="1" parent="1">
            <mxGeometry x="20" y="20" width="120" height="60" as="geometry"/>
            </mxCell></root></mxGraphModel>
            """;

    @Test
    void generatesWithFreshToolFreeSessionAndOmitsAttachmentMetadata() {
        RecordingChat chat = new RecordingChat(
                "{\"canvasXml\":\"<mxGraphModel><root><mxCell id=\\\"0\\\"/></root></mxGraphModel>\","
                        + "\"assistantMessage\":\"created\",\"payloadRef\":\"plain-1\"}");
        ChatPlainGenerationAdapter adapter = new ChatPlainGenerationAdapter(
                new ToolFreeChatModelInvoker(chat, "300025", "test-plain"));

        PlainGenerationResult result = adapter.generate(request(), event -> { });

        assertEquals("plain-1", result.payloadRef());
        assertEquals("created", result.assistantMessage());
        assertEquals("<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>",
                result.canvasXml());
        assertEquals(1, chat.createSessionCalls);
        assertTrue(chat.lastText.contains("PLAIN_SOURCE_FREE_GENERATION_V1"));
        assertTrue(chat.lastText.contains("PLAIN_EXECUTION_PROFILE: m2-plain-source-free"));
        assertTrue(chat.lastText.contains("CURRENT_MESSAGE_ATTACHMENTS: OMITTED_BY_SOURCE_FREE_CONTRACT"));
        assertTrue(chat.lastText.contains("CHARTBOOK_PROFILE_DATA"));
        assertTrue(chat.lastText.contains("keep labels short"));
        assertTrue(chat.lastText.contains("CONFIRMED_MEMORY_DATA"));
        assertTrue(chat.lastText.contains("use short labels"));
        assertFalse(chat.lastText.contains("SOURCE_AVAILABILITY"));
        assertFalse(chat.lastText.contains("SOURCE_BODY"));
        assertFalse(chat.lastText.contains("private-spec.pdf"));
        assertFalse(chat.lastText.contains("attachment-secret"));
        assertFalse(chat.lastText.contains(CANVAS_XML.trim()));
    }

    @Test
    void editReceivesTheExactPinnedCanvasXml() {
        RecordingChat chat = new RecordingChat(
                "{\"canvasXml\":\"<mxGraphModel><root><mxCell id=\\\"0\\\"/></root></mxGraphModel>\","
                        + "\"assistantMessage\":\"edited\",\"payloadRef\":\"plain-edit\"}");
        ChatPlainGenerationAdapter adapter = new ChatPlainGenerationAdapter(
                new ToolFreeChatModelInvoker(chat, "300025", "test-plain"));

        adapter.generate(request(PlainDrawAction.EDIT), event -> { });

        assertTrue(chat.lastText.contains("CANVAS_XML_DATA:"));
        assertTrue(chat.lastText.contains(CANVAS_XML.trim()));
    }

    @Test
    void rejectsAdditionalOutputFieldsBeforeReturningGenerationResult() {
        RecordingChat chat = new RecordingChat(
                "{\"canvasXml\":\"<mxGraphModel></mxGraphModel>\","
                        + "\"assistantMessage\":\"created\",\"payloadRef\":\"plain-1\","
                        + "\"sourceBody\":\"must-not-be-returned\"}");
        ChatPlainGenerationAdapter adapter = new ChatPlainGenerationAdapter(
                new ToolFreeChatModelInvoker(chat, "300025", "test-plain"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter.generate(request(), event -> { }));

        assertEquals("V2_PLAIN_MODEL_OUTPUT_INVALID", failure.getMessage());
    }

    @Test
    void rejectsNonMxGraphModelOutputBeforeReturningGenerationResult() {
        RecordingChat chat = new RecordingChat(
                "{\"canvasXml\":\"<svg></svg>\",\"assistantMessage\":\"created\","
                        + "\"payloadRef\":\"plain-1\"}");
        ChatPlainGenerationAdapter adapter = new ChatPlainGenerationAdapter(
                new ToolFreeChatModelInvoker(chat, "300025", "test-plain"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter.generate(request(), event -> { }));

        assertEquals("V2_PLAIN_MODEL_OUTPUT_INVALID", failure.getMessage());
    }

    private PlainGenerationRequest request() {
        return request(PlainDrawAction.CREATE);
    }

    private PlainGenerationRequest request(PlainDrawAction action) {
        return new PlainGenerationRequest(
                new FencedAttempt(
                        new TurnKey("owner-1", "conversation-1", "turn-1"),
                        AttemptLease.fromDatabaseClock(
                                "attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"),
                                Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                        2,
                        "input-digest",
                        new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy")),
                new BaseTurnContext(
                        new CurrentRequestContext("turn-1", "diagram-1",
                                new CurrentInstruction("draw a login flow")),
                        new AvailableContext<>(new CurrentMessageAttachmentsContext(
                                "attachment-binding",
                                List.of(new CurrentMessageAttachmentView(
                                        new OpaqueConversationFileRef("file-1"),
                                        "application/pdf", "private-spec.pdf"))), "attachments"),
                        new AbsentContext<>("no clarification"),
                        new AvailableContext<>(new TrustedCanvasContext(
                                1, 0, "one node", 2, "canvas-hash", CANVAS_XML), "canvas"),
                        new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                        new AvailableContext<>(new ConversationContext(
                                List.of("previous turn"), "previous summary"), "conversation"),
                        new AvailableContext<>(new ChartbookMembershipContext("chartbook-1", 1, 1),
                                "membership"),
                        new AvailableContext<>(new ChartbookProfileContext(
                                "keep labels short", "login flow", "team diagram", List.of("API"), "blue"),
                                "profile"),
                        new AvailableContext<>(new ConfirmedMemoryContext(List.of("use short labels")),
                                "memory"),
                        new ContextDiagnostics(List.of())),
                readSet(),
                new PlainDrawPlan(action, "draw a login flow"),
                PlainExecutionProfile.m2SourceFree());
    }

    private ContextReadSet readSet() {
        String digest = "a".repeat(64);
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.pinned(ContextSlice.SUMMARY, "summary-1", 1, digest),
                ContextSlicePin.pinned(ContextSlice.MEMBERSHIP, "membership-1", 1, digest),
                ContextSlicePin.pinned(ContextSlice.PROFILE, "profile-1", 1, digest),
                ContextSlicePin.pinned(ContextSlice.MEMORY, "memory-1", 1, digest));
    }

    private static final class RecordingChat implements IChatService {
        private final String response;
        private String lastText;
        private int createSessionCalls;

        private RecordingChat(String response) {
            this.response = response;
        }

        @Override
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
            return List.of();
        }

        @Override
        public boolean isAgentToolFree(String agentId) {
            return true;
        }

        @Override
        public String createSession(String agentId, String userId) {
            createSessionCalls++;
            return "fresh-session";
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
}
