package org.zipp.ai.application.turn.context;

import java.util.Set;

public record ActiveClarificationContext(Set<String> safeOptionLabels) {

    public ActiveClarificationContext {
        safeOptionLabels = Set.copyOf(safeOptionLabels == null ? Set.of() : safeOptionLabels);
        if (safeOptionLabels.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("safe option labels must not be blank");
        }
    }
}
