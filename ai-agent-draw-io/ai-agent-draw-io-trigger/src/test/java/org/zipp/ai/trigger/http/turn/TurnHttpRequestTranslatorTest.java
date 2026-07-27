package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TurnHttpRequestTranslatorTest {

    @Test
    void canonicalRequestPreservesOpaqueAttachmentOrderAndHiddenClarificationId() {
        UserTurnCommand command = new TurnHttpRequestTranslator().translate(new TurnHttpRequest(
                "turn-1", "legacy:session-1", "diagram-1", "client-1", "draw", "session-1",
                List.of("file-a", "file-b"), "clarification-1", List.of()));

        assertEquals(List.of("file-a", "file-b"), command.declarations().currentTurnAttachments()
                .stream().map(value -> value.value()).toList());
        assertEquals("clarification-1",
                ((ReplyToClarification) command.declarations().clarificationReply())
                        .clarificationId().value());
    }

    @Test
    void legacyOwnerAndCanvasFieldsAreNotCopiedIntoCanonicalCommand() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setUserId("attacker");
        request.setSessionId("session-1");
        request.setRequestId("turn-1");
        request.setClientMessageId("user-message-1");
        request.setResponseMessageId("agent-message-1");
        request.setDiagramId("diagram-1");
        request.setMessage("draw");
        request.setCanvasXml("client-authoritative-canvas");
        var selectedVersions = ChatRequestDTO.class.getDeclaredField("selectedLibraryVersionIds");
        selectedVersions.setAccessible(true);
        selectedVersions.set(request, List.of("version-1"));

        UserTurnCommand command = new TurnHttpRequestTranslator().translateProductChat(request);

        assertEquals("legacy:session-1", command.conversationReference());
        assertEquals("user-message-1", command.clientMessageId());
        assertEquals(List.of("version-1"), command.declarations().legacySelectedSources()
                .stream().map(value -> value.value()).toList());
        assertEquals("session-1", command.runtimeSessionId());
        assertFalse(command.declarations().currentTurnAttachments().contains(
                new org.zipp.ai.application.turn.OpaqueConversationFileRef("client-authoritative-canvas")));
    }

    @Test
    void legacySessionIdNeverEscapesItsAliasNamespace() {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setSessionId("conversation:opaque-session-value");
        request.setRequestId("turn-1");
        request.setResponseMessageId("client-1");
        request.setDiagramId("diagram-1");
        request.setMessage("draw");

        UserTurnCommand command = new TurnHttpRequestTranslator().translateProductChat(request);

        assertEquals("legacy:conversation:opaque-session-value", command.conversationReference());
    }

    @Test
    void explicitMemoryLanguageCreatesADeclaration() {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setSessionId("session-1");
        request.setRequestId("turn-1");
        request.setResponseMessageId("client-1");
        request.setDiagramId("diagram-1");
        request.setMessage("记住这个决定：所有服务使用事件命名约定");
        request.setMemoryChartbookId("chartbook-1");

        UserTurnCommand command = new TurnHttpRequestTranslator().translateProductChat(request);

        assertEquals(RememberDecisionDeclaration.class, command.declarations().memoryWrite().getClass());
    }

    @Test
    void canonicalV2RequestCanDeclareTheSameMemoryWrite() {
        UserTurnCommand command = new TurnHttpRequestTranslator().translate(new TurnHttpRequest(
                "turn-1",
                "conversation-1",
                "diagram-1",
                "client-1",
                "remember this decision: use event naming",
                "session-1",
                List.of(),
                null,
                List.of(),
                "chartbook-1"));

        RememberDecisionDeclaration declaration = (RememberDecisionDeclaration)
                command.declarations().memoryWrite();
        assertEquals("chartbook-1", declaration.chartbookId());
        assertEquals("use event naming", declaration.canonicalText());
        assertEquals("en", declaration.locale());
    }
}
