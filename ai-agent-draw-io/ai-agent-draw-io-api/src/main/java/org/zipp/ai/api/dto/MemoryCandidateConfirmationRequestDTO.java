package org.zipp.ai.api.dto;

/** Confirmation carries the immutable turn and declaration fence returned by the candidate view. */
public record MemoryCandidateConfirmationRequestDTO(
        String sourceConversationId,
        String sourceTurnId,
        String declarationDigest
) {
}
