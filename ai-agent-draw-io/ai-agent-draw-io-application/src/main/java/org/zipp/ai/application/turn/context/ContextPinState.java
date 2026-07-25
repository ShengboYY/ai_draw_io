package org.zipp.ai.application.turn.context;

/** State of a durable context slice pin, including explicit absence and revocation. */
public enum ContextPinState {
    ABSENT,
    PINNED,
    DEGRADED,
    REVOKED
}
