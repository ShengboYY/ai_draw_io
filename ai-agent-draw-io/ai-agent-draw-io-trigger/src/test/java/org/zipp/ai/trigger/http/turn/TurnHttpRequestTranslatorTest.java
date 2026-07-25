package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.ReplyToClarification;
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
        request.setResponseMessageId("client-1");
        request.setDiagramId("diagram-1");
        request.setMessage("draw");
        request.setCanvasXml("client-authoritative-canvas");
        var selectedVersions = ChatRequestDTO.class.getDeclaredField("selectedLibraryVersionIds");
        selectedVersions.setAccessible(true);
        selectedVersions.set(request, List.of("version-1"));

        UserTurnCommand command = new TurnHttpRequestTranslator().translateLegacy(request);

        assertEquals("legacy:session-1", command.conversationReference());
        assertEquals(List.of("version-1"), command.declarations().legacySelectedSources()
                .stream().map(value -> value.value()).toList());
        assertEquals("session-1", command.runtimeSessionId());
        assertFalse(command.declarations().currentTurnAttachments().contains(
                new org.zipp.ai.application.turn.OpaqueConversationFileRef("client-authoritative-canvas")));
    }
}
