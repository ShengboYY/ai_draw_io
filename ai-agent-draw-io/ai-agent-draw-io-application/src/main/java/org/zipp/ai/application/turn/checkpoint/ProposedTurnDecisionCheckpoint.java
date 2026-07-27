package org.zipp.ai.application.turn.checkpoint;

public record ProposedTurnDecisionCheckpoint(TurnDecisionCheckpoint value) {

    public ProposedTurnDecisionCheckpoint {
        if (value == null) {
            throw new IllegalArgumentException("decision checkpoint proposal must not be null");
        }
    }
}
