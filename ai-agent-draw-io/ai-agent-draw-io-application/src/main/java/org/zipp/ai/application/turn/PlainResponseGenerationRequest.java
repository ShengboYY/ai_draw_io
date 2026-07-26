package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;

/** Input visible to a source-free answer/review generation port. */
public record PlainResponseGenerationRequest(
        FencedAttempt attempt,
        BaseTurnContext context,
        ContextReadSet readSet,
        PlainResponsePlan plan,
        PlainExecutionProfile profile
) {

    public PlainResponseGenerationRequest {
        if (attempt == null || context == null || readSet == null || plan == null || profile == null) {
            throw new IllegalArgumentException("plain response generation request values must not be null");
        }
    }
}
