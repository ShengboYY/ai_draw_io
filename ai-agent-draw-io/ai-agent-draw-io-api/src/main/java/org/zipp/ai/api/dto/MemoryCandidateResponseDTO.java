package org.zipp.ai.api.dto;

import java.time.Instant;

/** Safe pending Memory candidate view; the digest and turn identity are confirmation fences. */
public record MemoryCandidateResponseDTO(
        String candidateId,
        String chartbookId,
        String diagramId,
        String sourceConversationId,
        String sourceTurnId,
        String decisionKey,
        String applicabilityStage,
        String scope,
        String canonicalText,
        String policyVersion,
        String declarationDigest,
        String status,
        long version,
        Instant expiresAt
) {
}
