package org.zipp.ai.application.turn;

public record CancelTurnCommand(TurnKey key, String reason) {

    public CancelTurnCommand {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        ContractValues.requiredText(reason, "reason");
    }
}
