package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.util.List;

/**
 * One post-commit shadow report. It is deliberately not a Memory proposal and has no persistence
 * or recall operation; a later M9 outbox can transport this report to an evaluation sink.
 */
public record MemoryShadowReport(
        TurnKey turn,
        String chartbookId,
        List<MemoryShadowObservation> observations,
        MemoryShadowMetrics metrics
) {
    public MemoryShadowReport {
        if (turn == null || chartbookId == null || chartbookId.isBlank() || observations == null
                || metrics == null) {
            throw new IllegalArgumentException("shadow report is invalid");
        }
        observations = List.copyOf(observations);
    }
}
