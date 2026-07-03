package org.zipp.ai.domain.account.service;

import java.time.Instant;

public final class UsageFailureState {

    private final int count;
    private final Instant lockedUntil;

    private UsageFailureState(int count, Instant lockedUntil) {
        this.count = Math.max(0, count);
        this.lockedUntil = lockedUntil;
    }

    public static UsageFailureState of(int count, Instant lockedUntil) {
        return new UsageFailureState(count, lockedUntil);
    }

    public int getCount() {
        return count;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now != null && now.isBefore(lockedUntil);
    }
}
