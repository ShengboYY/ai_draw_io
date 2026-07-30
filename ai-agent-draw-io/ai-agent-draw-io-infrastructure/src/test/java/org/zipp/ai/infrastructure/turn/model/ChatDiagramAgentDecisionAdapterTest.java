package org.zipp.ai.infrastructure.turn.model;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.CallDiagramTool;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentObservation;
import org.zipp.ai.application.turn.agent.DiagramAgentState;
import org.zipp.ai.application.turn.agent.DiagramAgentToolResult;
import org.zipp.ai.application.turn.agent.DiagramDraftStructure;
import org.zipp.ai.application.turn.agent.DiagramDraftView;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualIssue;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReview;
import org.zipp.ai.application.turn.agent.DraftRef;
import org.zipp.ai.application.turn.agent.InspectedDiagramCell;
import org.zipp.ai.application.turn.agent.SubmitDiagramCandidate;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDiagramAgentDecisionAdapterTest {

    @Test
    void parsesStrictCreateActionAndRendersTheFinalCompositionPrompt() {
        RecordingChat chat = new RecordingChat(
                "{\"action\":\"CALL_TOOL\",\"toolName\":\"create_draft\",\"arguments\":"
                        + "{\"canvasXml\":\"<mxGraphModel><root><mxCell id=\\\"0\\\"/>"
                        + "<mxCell id=\\\"1\\\" parent=\\\"0\\\"/></root></mxGraphModel>\"}}");
        ChatDiagramAgentDecisionAdapter adapter = new ChatDiagramAgentDecisionAdapter(
                new ToolFreeChatModelInvoker(chat, "300025", "test-agent-decision"));

        var action = adapter.decide(observation(), () -> false);

        CallDiagramTool call = assertInstanceOf(CallDiagramTool.class, action);
        assertInstanceOf(CreateDraftRequest.class, call.request());
        assertTrue(chat.lastText.contains("PLAIN_XML_CREATE_DRAFT_V2"));
        assertTrue(chat.lastText.contains("complete, presentation-ready diagram"));
        assertTrue(chat.lastText.contains("CREATE_DRAFT_SCHEMA"));
        assertTrue(chat.lastText.contains("not a rough intermediate sketch"));
        assertFalse(chat.lastText.contains("ACTION_PROTOCOL"));
        assertFalse(chat.lastText.contains("patch_draft"));
        assertFalse(chat.lastText.contains("inspect_draft"));
        assertFalse(chat.lastText.contains("REMAINING_STEPS"));
    }

    @Test
    void rendersOnlyGroundedTargetCellXmlForVisualRepair() {
        String digest = "sha256:" + "a".repeat(64);
        DiagramDraftView draft = new DiagramDraftView(new DraftRef("draft-1"), digest, 1);
        DiagramDraftStructure structure = new DiagramDraftStructure(1, 0, 3);
        DiagramDraftVisualReview review = new DiagramDraftVisualReview(
                digest,
                "REPAIR",
                true,
                "Move one node.",
                List.of(new DiagramDraftVisualIssue(
                        "LAYOUT_HIERARCHY",
                        "MAJOR",
                        List.of("node-a"),
                        "The node is visibly misplaced.",
                        "Move node-a into alignment.")),
                "",
                "test-reviewer");
        DiagramAgentToolResult targetContext = DiagramAgentToolResult.success(
                "inspect_draft",
                draft,
                structure,
                List.of(),
                List.of(new InspectedDiagramCell(
                        "node-a",
                        "Start",
                        "node",
                        "1",
                        "",
                        "",
                        10,
                        10,
                        80,
                        40,
                        "<mxCell id=\"node-a\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" "
                                + "as=\"geometry\"/></mxCell>")),
                "",
                false);
        DiagramAgentState state = new DiagramAgentState(
                request(),
                DiagramSkillBundle.empty(),
                draft,
                structure,
                review,
                targetContext,
                List.of(),
                List.of(digest),
                1,
                1,
                1,
                0,
                1,
                0);
        String prompt = new PlainGenerationPromptRenderer().render(
                new DiagramAgentObservation(
                        state,
                        List.of("inspect_draft", "patch_draft"),
                        7,
                        2,
                        1,
                        1));

        assertTrue(prompt.contains("PLAIN_XML_REPAIR_DRAFT_V2"));
        assertTrue(prompt.contains("TARGET_CELL_DATA"));
        assertTrue(prompt.contains("node-a"));
        assertTrue(prompt.contains("x=\\\"10\\\""));
        assertTrue(prompt.contains("PRESERVATION_CONTRACT"));
        assertTrue(prompt.contains("PATCH_DRAFT_SCHEMA"));
        assertFalse(prompt.contains("create_draft"));
        assertFalse(prompt.contains("inspect_draft"));
        assertFalse(prompt.contains("SUBMIT_CANDIDATE"));
        assertFalse(prompt.contains("CONVERSATION_DATA"));
    }

    @Test
    void parsesSubmitAndRejectsAdditionalFields() {
        ChatDiagramAgentDecisionAdapter adapter = new ChatDiagramAgentDecisionAdapter(
                new ToolFreeChatModelInvoker(new RecordingChat("unused"), "300025", "test"));

        assertInstanceOf(SubmitDiagramCandidate.class, adapter.parse(
                "{\"action\":\"SUBMIT_CANDIDATE\",\"draftRef\":\"draft-1\","
                        + "\"expectedDigest\":\"sha256:" + "a".repeat(64) + "\","
                        + "\"assistantMessage\":\"Done\"}"));
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                "{\"action\":\"SUBMIT_CANDIDATE\",\"draftRef\":\"draft-1\","
                        + "\"expectedDigest\":\"sha256:" + "a".repeat(64) + "\","
                        + "\"assistantMessage\":\"Done\",\"extra\":true}"));
    }

    @Test
    void rejectsReviewDraftBecauseReviewIsRuntimeDelegated() {
        ChatDiagramAgentDecisionAdapter adapter = new ChatDiagramAgentDecisionAdapter(
                new ToolFreeChatModelInvoker(new RecordingChat("unused"), "300025", "test"));

        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                "{\"action\":\"CALL_TOOL\",\"toolName\":\"review_draft\",\"arguments\":{"
                        + "\"draftRef\":\"draft-1\",\"expectedDigest\":\"sha256:"
                        + "a".repeat(64) + "\"}}"));
    }

    private DiagramAgentObservation observation() {
        DiagramAgentState state = new DiagramAgentState(
                request(),
                DiagramSkillBundle.empty(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0);
        return new DiagramAgentObservation(
                state,
                List.of("create_draft", "inspect_draft", "patch_draft"),
                8,
                3,
                1,
                1);
    }

    private PlainGenerationRequest request() {
        return new PlainGenerationRequest(
                attempt(),
                new BaseTurnContext(
                        new CurrentRequestContext(
                                "turn-1", "diagram-1", new CurrentInstruction("draw a flow")),
                        new AvailableContext<>(
                                new CurrentMessageAttachmentsContext("binding-1", List.of()),
                                "attachments"),
                        new AbsentContext<>("no clarification"),
                        new AvailableContext<>(
                                new TrustedCanvasContext(0, 0, "", 0, "", ""),
                                "canvas"),
                        new AvailableContext<>(
                                new ValidatedSelectionContext(false, 0), "selection"),
                        new AvailableContext<>(
                                new ConversationContext(List.of(), ""), "conversation"),
                        new AbsentContext<>("no membership"),
                        new AbsentContext<>("no profile"),
                        new AbsentContext<>("no memory"),
                        new ContextDiagnostics(List.of())),
                readSet(),
                new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"),
                PlainExecutionProfile.m2SourceFree());
    }

    private ContextReadSet readSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private FencedAttempt attempt() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, now.plusSeconds(60), 60_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static final class RecordingChat implements IChatService {
        private final String response;
        private String lastText;

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
        public List<String> handleMessage(
                String agentId,
                String userId,
                String sessionId,
                String message
        ) {
            return List.of(response);
        }

        @Override
        public Flowable<Event> handleMessageStream(
                String agentId,
                String userId,
                String sessionId,
                String message
        ) {
            return Flowable.empty();
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity command) {
            lastText = command.getTexts().get(0).getMessage();
            return List.of(response);
        }
    }
}
