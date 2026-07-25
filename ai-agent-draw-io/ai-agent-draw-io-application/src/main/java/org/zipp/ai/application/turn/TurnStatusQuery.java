package org.zipp.ai.application.turn;

public record TurnStatusQuery(TurnKey key) {

    public TurnStatusQuery {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
