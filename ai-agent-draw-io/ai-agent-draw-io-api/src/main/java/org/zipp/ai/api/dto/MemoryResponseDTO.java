package org.zipp.ai.api.dto;

/** User-visible materialized Memory view without exposing owner internals. */
public record MemoryResponseDTO(
        String memoryId,
        String chartbookId,
        String sourceConversationId,
        String sourceTurnId,
        String decisionKey,
        String applicabilityStage,
        String scope,
        String canonicalText,
        String status,
        long version
) {
}
