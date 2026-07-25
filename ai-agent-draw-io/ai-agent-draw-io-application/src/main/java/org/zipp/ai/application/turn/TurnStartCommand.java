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
        TurnDeclarations declarations,
        String inputBindingDigest
) {

    /** Compatibility constructor for callers that predate durable declaration recovery. */
    public TurnStartCommand(
            TurnKey key,
            String diagramId,
            TurnEngineAssignment assignment,
            String userMessage,
            String clientMessageId,
            List<OpaqueConversationFileRef> currentTurnAttachments,
            String inputBindingDigest
    ) {
        this(key, diagramId, assignment, userMessage, clientMessageId, currentTurnAttachments,
                new TurnDeclarations(currentTurnAttachments, new NoClarificationReply(), List.of(),
                        assignment == null ? new NoMemoryWrite() : assignment.memoryWrite()),
                inputBindingDigest);
    }

    public TurnStartCommand {
        if (key == null || assignment == null) {
            throw new IllegalArgumentException("key and assignment must not be null");
        }
        if (!key.equals(assignment.key())) {
            throw new IllegalArgumentException("turn key must match the pinned assignment");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        if (!diagramId.equals(assignment.diagramId())) {
            // The first claim and every retry must stay bound to the original diagram.
            throw new IllegalArgumentException("diagram must match the pinned assignment");
        }
        ContractValues.requiredText(userMessage, "userMessage");
        ContractValues.requiredText(clientMessageId, "clientMessageId");
        ContractValues.requiredText(inputBindingDigest, "inputBindingDigest");
        currentTurnAttachments = List.copyOf(
                currentTurnAttachments == null ? List.of() : currentTurnAttachments);
        if (declarations == null
                || !currentTurnAttachments.equals(declarations.currentTurnAttachments())
                || !assignment.memoryWrite().equals(declarations.memoryWrite())) {
            throw new IllegalArgumentException("turn declarations must match the pinned assignment");
        }
        Set<String> attachmentRefs = new HashSet<>();
        for (OpaqueConversationFileRef attachment : currentTurnAttachments) {
            if (attachment == null || !attachmentRefs.add(attachment.value())) {
                throw new IllegalArgumentException("currentTurnAttachments must contain unique non-null refs");
            }
        }
    }
}
