package org.zipp.ai.application.turn;

/** Server-resolved canonical conversation identity and its active diagram binding. */
public record ConversationRef(
        String id,
        String ownerKey,
        String diagramId,
        ConversationStatus status
) {

    public ConversationRef {
        ContractValues.requiredText(id, "id");
        ContractValues.requiredText(ownerKey, "ownerKey");
        ContractValues.requiredText(diagramId, "diagramId");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public boolean isActive() {
        return status == ConversationStatus.ACTIVE;
    }
}
