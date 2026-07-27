package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.springframework.http.HttpStatus;

/** Transport result for a durable turn status query. */
public record TurnHttpStatusResult(
        HttpStatus httpStatus,
        TurnStatusQueryOutcome outcome
) {

    public TurnHttpStatusResult {
        if (httpStatus == null || outcome == null) {
            throw new IllegalArgumentException("status response values must not be null");
        }
    }
}
