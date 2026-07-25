package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.demand.CurrentInstruction;

/** Server-owned request facts used to build every consumer projection. */
public record CurrentRequestContext(
        String turnId,
        String diagramId,
        CurrentInstruction instruction
) {

    public CurrentRequestContext {
        ContextValues.requiredText(turnId, "turnId");
        ContextValues.requiredText(diagramId, "diagramId");
        if (instruction == null) {
            throw new IllegalArgumentException("instruction must not be null");
        }
    }
}
