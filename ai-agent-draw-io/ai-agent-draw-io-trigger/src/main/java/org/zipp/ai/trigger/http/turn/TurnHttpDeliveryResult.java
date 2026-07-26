package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnSubmission;
import org.springframework.http.HttpStatus;

import java.util.List;

/** Result of a sync delivery attempt, keeping delivery receipts separate from product outcome. */
public record TurnHttpDeliveryResult(TurnSubmission submission, List<TurnEvent> events, boolean detached) {

    public TurnHttpDeliveryResult {
        if (submission == null) {
            throw new IllegalArgumentException("submission must not be null");
        }
        events = List.copyOf(events == null ? List.of() : events);
    }

    /** Returns the status mapping without changing the durable application outcome. */
    public HttpStatus responseStatus() {
        return new TurnHttpSubmissionMapper().map(submission).httpStatus();
    }

    /** Accepted/running submissions require the durable status endpoint contract. */
    public boolean statusEndpointRequired() {
        return new TurnHttpSubmissionMapper().map(submission).statusEndpointRequired();
    }
}
