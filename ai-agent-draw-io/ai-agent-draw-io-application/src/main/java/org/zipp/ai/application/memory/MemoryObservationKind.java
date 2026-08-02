package org.zipp.ai.application.memory;

/** Describes how an observation entered the trusted application boundary. */
public enum MemoryObservationKind {
    EXPLICIT,
    INFERRED,
    MIGRATED,
    USER_EDIT;

    public boolean isExplicit() {
        return this == EXPLICIT || this == MIGRATED || this == USER_EDIT;
    }
}
