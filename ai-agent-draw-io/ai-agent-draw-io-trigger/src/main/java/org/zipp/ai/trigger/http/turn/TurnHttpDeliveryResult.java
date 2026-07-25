package org.zipp.ai.trigger.http.turn;

import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnSubmission;

import java.util.List;

/** Result of a sync delivery attempt, keeping delivery receipts separate from product outcome. */
public record TurnHttpDeliveryResult(TurnSubmission submission, List<TurnEvent> events, boolean detached) {

    public TurnHttpDeliveryResult {
        if (submission == null) {
            throw new IllegalArgumentException("submission must not be null");
        }
        events = List.copyOf(events == null ? List.of() : events);
    }
}
