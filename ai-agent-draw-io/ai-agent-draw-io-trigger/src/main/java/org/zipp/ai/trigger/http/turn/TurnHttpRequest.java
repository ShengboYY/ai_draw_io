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
        String memoryChartbookId,
        List<String> requestedSkillNames
) {

    public TurnHttpRequest {
        currentTurnAttachmentRefs = List.copyOf(
                currentTurnAttachmentRefs == null ? List.of() : currentTurnAttachmentRefs);
        legacySelectedSourceIds = List.copyOf(
                legacySelectedSourceIds == null ? List.of() : legacySelectedSourceIds);
        requestedSkillNames = List.copyOf(
                requestedSkillNames == null ? List.of() : requestedSkillNames);
    }

    /** Compatibility constructor for callers that predate V2 skill declarations. */
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
                legacySelectedSourceIds, null, List.of());
    }

    /** Compatibility constructor for callers that declare Memory but not V2 skills. */
    public TurnHttpRequest(
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
        this(turnId, conversationReference, diagramId, clientMessageId, content,
                runtimeSessionId, currentTurnAttachmentRefs, clarificationId,
                legacySelectedSourceIds, memoryChartbookId, List.of());
    }
}
