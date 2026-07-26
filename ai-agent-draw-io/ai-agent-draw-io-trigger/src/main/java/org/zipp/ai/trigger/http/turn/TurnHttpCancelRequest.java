package org.zipp.ai.trigger.http.turn;

/** Explicit cancellation request; it carries no client-supplied owner identity. */
public record TurnHttpCancelRequest(TurnHttpControlRequest target, String reason) {

    public TurnHttpCancelRequest {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
