package org.zipp.ai.trigger.http.turn;

/** Body for an explicit cancel; owner and turn scope come from the route and session. */
public record TurnHttpCancelBody(String reason) {

    public TurnHttpCancelBody {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
