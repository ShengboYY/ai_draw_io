package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.Objects;

public record DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                                  VisualObservationTarget target, String question) {
    public DirectSourceCommand {
        owner = Objects.requireNonNull(owner, "owner");
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        target = Objects.requireNonNull(target, "target");
        question = required(question, "question");
        if (question.length() > 2_000) throw new IllegalArgumentException("question is too long");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
