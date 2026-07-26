package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.springframework.http.HttpStatus;

/** Transport result for an explicit, owner-fenced turn cancellation. */
public record TurnHttpCancelResult(
        HttpStatus httpStatus,
        CancelTurnOutcome outcome
) {

    public TurnHttpCancelResult {
        if (httpStatus == null || outcome == null) {
            throw new IllegalArgumentException("cancel response values must not be null");
        }
    }
}
