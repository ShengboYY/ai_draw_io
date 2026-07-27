package org.zipp.ai.application.turn.context;

import java.util.List;

public record ContextDiagnostics(List<String> codes) {

    public ContextDiagnostics {
        codes = List.copyOf(codes == null ? List.of() : codes);
        if (codes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("diagnostic codes must not be blank");
        }
    }
}
