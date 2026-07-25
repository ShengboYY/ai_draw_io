package org.zipp.ai.application.turn;

/** Stable, non-content lifecycle categories used by the M1 execution contract. */
public enum TurnLifecycleTraceType {
    ASSIGNMENT,
    CLAIM,
    ATTEMPT_STARTED,
    LEASE_RENEWED,
    TAKEOVER,
    DETACH,
    CANCEL,
    ATTEMPT_COMPLETED
}
