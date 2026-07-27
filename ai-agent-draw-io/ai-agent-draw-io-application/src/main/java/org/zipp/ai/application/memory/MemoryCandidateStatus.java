package org.zipp.ai.application.memory;

/** Durable lifecycle of a user-confirmed Memory candidate. */
public enum MemoryCandidateStatus {
    PENDING,
    MATERIALIZED,
    EXPIRED,
    REVOKED
}
