package org.zipp.ai.application.turn;

/** Input visible to the tool-free Plain model port. */
public record PlainGenerationRequest(
        FencedAttempt attempt,
        PlainDrawPlan plan,
        PlainExecutionProfile profile
) {

    public PlainGenerationRequest {
        if (attempt == null || plan == null || profile == null) {
            throw new IllegalArgumentException("plain generation request values must not be null");
        }
    }
}
