package org.zipp.ai.application.turn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> attachmentRefs = new HashSet<>();
        for (OpaqueConversationFileRef attachment : currentTurnAttachments) {
            if (attachment == null || !attachmentRefs.add(attachment.value())) {
                throw new IllegalArgumentException("currentTurnAttachments must contain unique non-null refs");
            }
        }
    }
}
