package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.Objects;

/** Visual questions are bounded data; image text never becomes a tool or system instruction. */
public record VisualObservationCommand(CatalogOwner owner, String requestId, String runId,
                                       VisualObservationPurpose purpose, String question,
                                       List<VisualObservationTarget> targets, int maximumObservations) {
    public VisualObservationCommand {
        owner = Objects.requireNonNull(owner, "owner");
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        purpose = Objects.requireNonNull(purpose, "purpose");
        question = required(question, "question");
        if (question.length() > 2_000) throw new IllegalArgumentException("question is too long");
        targets = List.copyOf(targets == null ? List.of() : targets);
        if (targets.isEmpty() || targets.size() > 4) {
            throw new IllegalArgumentException("visual target count must be between 1 and 4");
        }
        if (maximumObservations < 1 || maximumObservations > 32) {
            throw new IllegalArgumentException("maximumObservations must be between 1 and 32");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
