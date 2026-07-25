package org.zipp.ai.application.turn;

import java.util.List;

public record TurnDeclarations(
        List<OpaqueConversationFileRef> currentTurnAttachments,
        ClarificationReplyDeclaration clarificationReply,
        List<UntrustedLegacyVersionDeclaration> legacySelectedSources,
        MemoryWriteDeclaration memoryWrite
) {

    public TurnDeclarations {
        currentTurnAttachments = List.copyOf(currentTurnAttachments == null ? List.of() : currentTurnAttachments);
        legacySelectedSources = List.copyOf(legacySelectedSources == null ? List.of() : legacySelectedSources);
        if (clarificationReply == null || memoryWrite == null) {
            throw new IllegalArgumentException("clarificationReply and memoryWrite must not be null");
        }
    }

    public static TurnDeclarations empty() {
        return new TurnDeclarations(List.of(), new NoClarificationReply(), List.of(), new NoMemoryWrite());
    }
}
