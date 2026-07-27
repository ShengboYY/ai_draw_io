package org.zipp.ai.trigger.http.turn;

import org.springframework.http.HttpStatus;
import org.zipp.ai.application.turn.TurnSubmission;

/** HTTP disposition for a typed application submission outcome. */
public record TurnHttpSubmissionResult(
        HttpStatus httpStatus,
        TurnSubmission submission,
        boolean statusEndpointRequired
) {

    public TurnHttpSubmissionResult {
        if (httpStatus == null || submission == null) {
            throw new IllegalArgumentException("httpStatus and submission must not be null");
        }
    }
}
