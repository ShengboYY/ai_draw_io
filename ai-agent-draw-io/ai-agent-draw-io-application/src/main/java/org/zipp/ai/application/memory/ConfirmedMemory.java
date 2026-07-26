package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

/** User-owned confirmed decision; this is context data, never Evidence/source data. */
public record ConfirmedMemory(
        String memoryId,
        TurnKey sourceTurn,
        String chartbookId,
        String decisionKey,
        String applicabilityStage,
        String scope,
        String canonicalText,
        ConfirmedMemoryStatus status,
        long version
) {
    public ConfirmedMemory {
        if (memoryId == null || sourceTurn == null || chartbookId == null || decisionKey == null
                || canonicalText == null || status == null || version < 1) {
            throw new IllegalArgumentException("invalid confirmed memory");
        }
    }
}
