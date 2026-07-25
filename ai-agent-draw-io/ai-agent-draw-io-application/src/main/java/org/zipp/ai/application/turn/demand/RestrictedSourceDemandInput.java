package org.zipp.ai.application.turn.demand;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Physical input boundary for demand interpretation. It deliberately excludes conversation,
 * profile, memory, source availability, file body, retrieval, and citation data.
 */
public record RestrictedSourceDemandInput(
        CurrentInstruction instruction,
        List<OpaqueConversationFileRef> currentMessageAttachments,
        Optional<String> chartbookMembership,
        Set<String> activeClarificationLabels
) {

    public RestrictedSourceDemandInput {
        if (instruction == null || chartbookMembership == null || activeClarificationLabels == null) {
            throw new IllegalArgumentException("restricted demand input values must not be null");
        }
        currentMessageAttachments = List.copyOf(
                currentMessageAttachments == null ? List.of() : currentMessageAttachments);
        chartbookMembership = chartbookMembership.map(String::trim).filter(value -> !value.isBlank());
        activeClarificationLabels = Set.copyOf(activeClarificationLabels);
    }
}
