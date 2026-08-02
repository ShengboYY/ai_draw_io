package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

/** Fenced durable work item loaded without copying an entire raw conversation. */
public record AutoMemoryExtractionLease(
        String workId,
        String workerId,
        long version,
        int attemptCount,
        TurnKey turn,
        String diagramId,
        String chartbookId,
        String userContent,
        String explicitCanonicalText
) {
    public AutoMemoryExtractionLease {
        required(workId, "workId");
        required(workerId, "workerId");
        if (version < 1 || attemptCount < 1 || turn == null) {
            throw new IllegalArgumentException("lease identity is invalid");
        }
        required(diagramId, "diagramId");
        required(userContent, "userContent");
        chartbookId = nullableTrimmed(chartbookId);
        explicitCanonicalText = nullableTrimmed(explicitCanonicalText);
    }

    public boolean hasExplicitObservation() {
        return explicitCanonicalText != null;
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String nullableTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
