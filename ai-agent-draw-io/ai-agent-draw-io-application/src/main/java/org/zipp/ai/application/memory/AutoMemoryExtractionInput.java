package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;

/** Server-owned post-commit input presented to the isolated Memory extractor. */
public record AutoMemoryExtractionInput(
        TurnKey turn,
        String diagramId,
        String chartbookId,
        String userContent,
        ModelInputBinding modelInputBinding
) {
    public AutoMemoryExtractionInput {
        if (turn == null || modelInputBinding == null) {
            throw new IllegalArgumentException("turn and modelInputBinding must not be null");
        }
        required(diagramId, "diagramId");
        required(userContent, "userContent");
        chartbookId = nullableTrimmed(chartbookId);
        if (!modelInputBinding.isBound() || !turn.equals(modelInputBinding.turnKey())) {
            throw new IllegalArgumentException("model input must be bound to the extraction turn");
        }
    }

    public boolean hasChartbook() {
        return chartbookId != null;
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
