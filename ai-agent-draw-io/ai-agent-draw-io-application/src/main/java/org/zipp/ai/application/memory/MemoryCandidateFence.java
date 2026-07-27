package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

/** Owner, turn and declaration fence required for every candidate mutation. */
public record MemoryCandidateFence(
        TurnKey turn,
        String chartbookId,
        String candidateId,
        String declarationDigest
) {
    public MemoryCandidateFence {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        required(chartbookId, "chartbookId");
        required(candidateId, "candidateId");
        required(declarationDigest, "declarationDigest");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
