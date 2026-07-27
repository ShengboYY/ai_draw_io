package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.execution.TurnHandle;

/**
 * Transport-neutral submission receipt. The process-local handle is present only when this call
 * claimed and started a new V2 attempt; retries that observe existing work have no local handle.
 */
public record TurnDeliveryExecution(TurnSubmission submission, TurnHandle handle) {

    public TurnDeliveryExecution {
        if (submission == null) {
            throw new IllegalArgumentException("submission must not be null");
        }
    }
}
