package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Server-owned post-commit input presented to the isolated Memory extractor. */
public record AutoMemoryExtractionInput(
        TurnKey turn,
        String diagramId,
        String chartbookId,
        String userContent,
        List<AutoMemoryExtractionCandidate> existingCandidates,
        ModelInputBinding modelInputBinding
) {
    public static final int MAX_EXISTING_CANDIDATES = 32;

    public AutoMemoryExtractionInput {
        if (turn == null || modelInputBinding == null) {
            throw new IllegalArgumentException("turn and modelInputBinding must not be null");
        }
        required(diagramId, "diagramId");
        required(userContent, "userContent");
        chartbookId = nullableTrimmed(chartbookId);
        existingCandidates = existingCandidates == null
                ? List.of() : List.copyOf(existingCandidates);
        if (existingCandidates.size() > MAX_EXISTING_CANDIDATES) {
            throw new IllegalArgumentException("too many existing Memory candidates");
        }
        Set<String> candidateKeys = new HashSet<>();
        for (AutoMemoryExtractionCandidate candidate : existingCandidates) {
            if (candidate.scopeType() == MemoryScopeType.CHARTBOOK && chartbookId == null) {
                throw new IllegalArgumentException("Chartbook candidate requires a Chartbook");
            }
            if (!candidateKeys.add(candidate.scopeType() + "\u001f" + candidate.semanticKey())) {
                throw new IllegalArgumentException("duplicate existing Memory candidate");
            }
        }
        if (!modelInputBinding.isBound() || !turn.equals(modelInputBinding.turnKey())) {
            throw new IllegalArgumentException("model input must be bound to the extraction turn");
        }
    }

    public AutoMemoryExtractionInput(
            TurnKey turn,
            String diagramId,
            String chartbookId,
            String userContent,
            ModelInputBinding modelInputBinding
    ) {
        this(turn, diagramId, chartbookId, userContent, List.of(), modelInputBinding);
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
