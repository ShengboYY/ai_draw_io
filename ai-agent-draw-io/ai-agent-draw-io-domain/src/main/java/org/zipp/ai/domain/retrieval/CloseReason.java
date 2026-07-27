package org.zipp.ai.domain.retrieval;

public enum CloseReason {
    COMMITTED,
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    CLIENT_DISCONNECTED,
    FAILED
}
