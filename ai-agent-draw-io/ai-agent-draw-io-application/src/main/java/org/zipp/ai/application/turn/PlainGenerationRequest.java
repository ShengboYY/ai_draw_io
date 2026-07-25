package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;

/** Input visible to the tool-free Plain model port. */
public record PlainGenerationRequest(
        FencedAttempt attempt,
        BaseTurnContext context,
        ContextReadSet readSet,
        PlainDrawPlan plan,
        PlainExecutionProfile profile
) {

    public PlainGenerationRequest {
        if (attempt == null || context == null || readSet == null || plan == null || profile == null) {
            throw new IllegalArgumentException("plain generation request values must not be null");
        }
    }
}
