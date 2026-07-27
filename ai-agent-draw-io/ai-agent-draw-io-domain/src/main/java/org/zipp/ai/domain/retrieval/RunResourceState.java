package org.zipp.ai.domain.retrieval;

/** Lifecycle of resources owned by one grounded request. */
public enum RunResourceState {
    OPEN,
    PREPARED,
    COMMITTING,
    CLOSED
}
