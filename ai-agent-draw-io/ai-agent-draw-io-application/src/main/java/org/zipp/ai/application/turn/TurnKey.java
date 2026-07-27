package org.zipp.ai.application.turn;

/** Stable product idempotency key; aliases never enter this value. */
public record TurnKey(String ownerKey, String canonicalConversationId, String turnId) {

    public TurnKey {
        ContractValues.requiredText(ownerKey, "ownerKey");
        ContractValues.requiredText(canonicalConversationId, "canonicalConversationId");
        ContractValues.requiredText(turnId, "turnId");
    }

    public static TurnKey of(AuthenticatedActor actor, ConversationRef conversation, String turnId) {
        if (!actor.ownerKey().equals(conversation.ownerKey())) {
            throw new IllegalArgumentException("actor and conversation owner do not match");
        }
        return new TurnKey(actor.ownerKey(), conversation.id(), turnId);
    }
}
