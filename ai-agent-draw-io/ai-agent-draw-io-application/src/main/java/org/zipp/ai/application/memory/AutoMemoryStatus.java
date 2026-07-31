package org.zipp.ai.application.memory;

/** Automatic Memory lifecycle; only ACTIVE items are eligible for recall. */
public enum AutoMemoryStatus {
    OBSERVED,
    ACTIVE,
    DISABLED,
    DELETED
}
