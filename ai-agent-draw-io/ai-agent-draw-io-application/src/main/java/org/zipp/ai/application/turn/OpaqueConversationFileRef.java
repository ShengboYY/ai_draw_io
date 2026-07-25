package org.zipp.ai.application.turn;

/** Opaque file identity supplied by the server-owned Conversation File scope. */
public record OpaqueConversationFileRef(String value) {

    public OpaqueConversationFileRef {
        ContractValues.requiredText(value, "value");
    }
}
