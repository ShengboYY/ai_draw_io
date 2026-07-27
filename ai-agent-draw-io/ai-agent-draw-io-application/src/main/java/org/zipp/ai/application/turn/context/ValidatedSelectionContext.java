package org.zipp.ai.application.turn.context;

public record ValidatedSelectionContext(boolean available, int selectedElementCount) {

    public ValidatedSelectionContext {
        if (selectedElementCount < 0) {
            throw new IllegalArgumentException("selectedElementCount must not be negative");
        }
    }
}
