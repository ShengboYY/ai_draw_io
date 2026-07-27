package org.zipp.ai.application.turn;

/** Stable turn identity used by control-plane outcomes without claiming a status snapshot. */
public record TurnStatusRef(TurnKey key) {

    public TurnStatusRef {
        if (key == null) {
            throw new IllegalArgumentException("turn status reference key must not be null");
        }
    }
}
