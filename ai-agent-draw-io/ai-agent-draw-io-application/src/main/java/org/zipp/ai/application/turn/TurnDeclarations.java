package org.zipp.ai.application.turn;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record TurnDeclarations(
        List<OpaqueConversationFileRef> currentTurnAttachments,
        ClarificationReplyDeclaration clarificationReply,
        List<UntrustedLegacyVersionDeclaration> legacySelectedSources,
        MemoryWriteDeclaration memoryWrite
) {

    public TurnDeclarations {
        currentTurnAttachments = List.copyOf(currentTurnAttachments == null ? List.of() : currentTurnAttachments);
        Set<String> attachmentRefs = new HashSet<>();
        for (OpaqueConversationFileRef attachment : currentTurnAttachments) {
            // Validate declaration shape before admission can persist sticky assignment state.
            if (attachment == null || !attachmentRefs.add(attachment.value())) {
                throw new IllegalArgumentException("currentTurnAttachments must contain unique non-null refs");
            }
        }
        legacySelectedSources = List.copyOf(legacySelectedSources == null ? List.of() : legacySelectedSources);
        if (clarificationReply == null || memoryWrite == null) {
            throw new IllegalArgumentException("clarificationReply and memoryWrite must not be null");
        }
    }

    public static TurnDeclarations empty() {
        return new TurnDeclarations(List.of(), new NoClarificationReply(), List.of(), new NoMemoryWrite());
    }
}
