package org.zipp.ai.application.turn.context;

import java.util.List;

/** Only confirmed working decisions may enter this context slice. */
public record ConfirmedMemoryContext(List<String> decisions) {

    public ConfirmedMemoryContext {
        decisions = List.copyOf(decisions == null ? List.of() : decisions);
        if (decisions.size() > 8 || decisions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("confirmed memory exceeds the bounded limit");
        }
    }
}
