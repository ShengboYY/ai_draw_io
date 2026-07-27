package org.zipp.ai.application.turn;

import java.time.Instant;

public record TurnStatusView(
        TurnKey key,
        TurnStatus status,
        String attemptId,
        long attemptEpoch,
        String terminalCode,
        String terminalPayloadRef,
        Instant updatedAt
) {

    public TurnStatusView {
        if (key == null || status == null || attemptEpoch < 0 || updatedAt == null) {
            throw new IllegalArgumentException("invalid turn status view");
        }
    }
}
