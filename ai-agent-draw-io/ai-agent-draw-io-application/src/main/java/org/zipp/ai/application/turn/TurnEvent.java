package org.zipp.ai.application.turn;

import java.time.Instant;

public record TurnEvent(String type, String payload, Instant occurredAt) {

    public TurnEvent {
        ContractValues.requiredText(type, "type");
        if (payload == null || occurredAt == null) {
            throw new IllegalArgumentException("payload and occurredAt must not be null");
        }
    }
}
