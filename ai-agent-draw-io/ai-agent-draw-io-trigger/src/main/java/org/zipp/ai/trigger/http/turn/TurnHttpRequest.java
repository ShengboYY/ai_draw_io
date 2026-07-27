package org.zipp.ai.trigger.http.turn;

import java.util.List;

/** Transport DTO for the canonical turn translator; it contains no authenticated owner field. */
public record TurnHttpRequest(
        String turnId,
        String conversationReference,
        String diagramId,
        String clientMessageId,
        String content,
        String runtimeSessionId,
        List<String> currentTurnAttachmentRefs,
        String clarificationId,
        List<String> legacySelectedSourceIds,
        String memoryChartbookId
) {

    public TurnHttpRequest {
        currentTurnAttachmentRefs = List.copyOf(
                currentTurnAttachmentRefs == null ? List.of() : currentTurnAttachmentRefs);
        legacySelectedSourceIds = List.copyOf(
                legacySelectedSourceIds == null ? List.of() : legacySelectedSourceIds);
    }

    /** Compatibility constructor for callers that do not declare a Memory target. */
    public TurnHttpRequest(
            String turnId,
            String conversationReference,
            String diagramId,
            String clientMessageId,
            String content,
            String runtimeSessionId,
            List<String> currentTurnAttachmentRefs,
            String clarificationId,
            List<String> legacySelectedSourceIds
    ) {
        this(turnId, conversationReference, diagramId, clientMessageId, content,
                runtimeSessionId, currentTurnAttachmentRefs, clarificationId,
                legacySelectedSourceIds, null);
    }
}
