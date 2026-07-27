package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.time.Instant;
import java.util.List;

/**
 * Post-terminal input for the future shadow observer. It carries only the owning Chartbook and
 * bounded extractor output, never source material, Profile fields, or an implicit confirmation.
 */
public record MemoryShadowTurnCommitted(
        TurnKey turn,
        String chartbookId,
        Instant committedAt,
        List<MemoryShadowCandidate> candidates
) {
    public MemoryShadowTurnCommitted {
        if (turn == null || chartbookId == null || chartbookId.isBlank() || committedAt == null
                || candidates == null) {
            throw new IllegalArgumentException("committed shadow input is invalid");
        }
        candidates = List.copyOf(candidates);
    }
}
