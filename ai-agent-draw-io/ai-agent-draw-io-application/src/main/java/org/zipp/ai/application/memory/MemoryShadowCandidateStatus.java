package org.zipp.ai.application.memory;

/** Outcome of evaluating one automatic candidate in shadow mode. */
public enum MemoryShadowCandidateStatus {
    SHADOW_ACCEPTED,
    DUPLICATE,
    CONFLICT,
    REJECTED
}
