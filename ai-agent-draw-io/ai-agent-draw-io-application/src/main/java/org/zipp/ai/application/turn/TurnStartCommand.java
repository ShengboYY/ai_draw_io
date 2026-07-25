package org.zipp.ai.application.turn;

import java.util.List;

public record TurnStartCommand(
        TurnKey key,
        String diagramId,
        TurnEngineAssignment assignment,
        String userMessage,
        String clientMessageId,
        List<OpaqueConversationFileRef> currentTurnAttachments,
        String inputBindingDigest
) {

    public TurnStartCommand {
        if (key == null || assignment == null) {
            throw new IllegalArgumentException("key and assignment must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        ContractValues.requiredText(userMessage, "userMessage");
        ContractValues.requiredText(clientMessageId, "clientMessageId");
        ContractValues.requiredText(inputBindingDigest, "inputBindingDigest");
        currentTurnAttachments = List.copyOf(
                currentTurnAttachments == null ? List.of() : currentTurnAttachments);
    }
}
