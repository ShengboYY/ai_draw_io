package org.zipp.ai.application.turn;

/** Binds a returned DB-clock lease to the caller's monotonic call start. */
public record LeaseTimingAnchor(long callStartedNanos, AttemptLease lease) {

    public LeaseTimingAnchor {
        if (lease == null) {
            throw new IllegalArgumentException("lease must not be null");
        }
    }
}
