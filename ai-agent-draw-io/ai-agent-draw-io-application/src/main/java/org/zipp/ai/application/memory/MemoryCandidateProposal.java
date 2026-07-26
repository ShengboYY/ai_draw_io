package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;

/** Durable candidate view. Scrubbed terminal states always expose a null payload. */
public record MemoryCandidateProposal(
        String candidateId,
        TurnKey turn,
        String chartbookId,
        String diagramId,
        String decisionKey,
        String applicabilityStage,
        String scope,
        String canonicalText,
        String policyVersion,
        String declarationDigest,
        MemoryCandidateStatus status,
        long version,
        Instant expiresAt,
        Instant retainUntil,
        Instant payloadDeletedAt,
        String materializedMemoryId
) {
    public MemoryCandidateProposal {
        if (candidateId == null || turn == null || chartbookId == null || diagramId == null || status == null
                || version < 1 || expiresAt == null || retainUntil == null) {
            throw new IllegalArgumentException("invalid memory candidate");
        }
        if (status != MemoryCandidateStatus.PENDING && canonicalText != null) {
            throw new IllegalArgumentException("scrubbed candidate must not retain payload");
        }
    }
}
