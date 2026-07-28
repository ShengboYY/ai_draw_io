package org.zipp.ai.application.turn;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record TurnDeclarations(
        List<OpaqueConversationFileRef> currentTurnAttachments,
        ClarificationReplyDeclaration clarificationReply,
        List<UntrustedLegacyVersionDeclaration> legacySelectedSources,
        MemoryWriteDeclaration memoryWrite,
        List<RequestedDiagramSkill> requestedDiagramSkills
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
        requestedDiagramSkills = List.copyOf(
                requestedDiagramSkills == null ? List.of() : requestedDiagramSkills);
        if (requestedDiagramSkills.size() > 4) {
            throw new IllegalArgumentException("at most four diagram skills may be requested");
        }
        Set<String> requestedNames = new HashSet<>();
        for (RequestedDiagramSkill skill : requestedDiagramSkills) {
            // A duplicate declaration would otherwise make retry bindings order-sensitive.
            if (skill == null || !requestedNames.add(skill.value())) {
                throw new IllegalArgumentException("requestedDiagramSkills must be unique and non-null");
            }
        }
        if (clarificationReply == null || memoryWrite == null) {
            throw new IllegalArgumentException("clarificationReply and memoryWrite must not be null");
        }
    }

    /** Compatibility constructor for callers that predate explicit V2 skill declarations. */
    public TurnDeclarations(
            List<OpaqueConversationFileRef> currentTurnAttachments,
            ClarificationReplyDeclaration clarificationReply,
            List<UntrustedLegacyVersionDeclaration> legacySelectedSources,
            MemoryWriteDeclaration memoryWrite
    ) {
        this(currentTurnAttachments, clarificationReply, legacySelectedSources, memoryWrite, List.of());
    }

    public static TurnDeclarations empty() {
        return new TurnDeclarations(
                List.of(), new NoClarificationReply(), List.of(), new NoMemoryWrite(), List.of());
    }
}
