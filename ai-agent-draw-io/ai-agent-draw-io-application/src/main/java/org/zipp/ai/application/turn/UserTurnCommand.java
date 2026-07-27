package org.zipp.ai.application.turn;

/** Caller input after transport mapping, before server-side conversation resolution. */
public record UserTurnCommand(
        String turnId,
        String conversationReference,
        String diagramId,
        String clientMessageId,
        String content,
        String runtimeSessionId,
        TurnDeclarations declarations
) {

    public UserTurnCommand {
        ContractValues.requiredText(turnId, "turnId");
        ContractValues.requiredText(conversationReference, "conversationReference");
        ContractValues.requiredText(diagramId, "diagramId");
        ContractValues.requiredText(clientMessageId, "clientMessageId");
        ContractValues.requiredText(content, "content");
        if (declarations == null) {
            throw new IllegalArgumentException("declarations must not be null");
        }
    }
}
