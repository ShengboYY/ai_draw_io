package org.zipp.ai.domain.account.service;

import java.time.Duration;

public final class UsageLimitRule {

    private final int maxEvents;
    private final Duration window;

    private UsageLimitRule(int maxEvents, Duration window) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxEvents = maxEvents;
        this.window = window;
    }

    public static UsageLimitRule of(int maxEvents, Duration window) {
        return new UsageLimitRule(maxEvents, window);
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public Duration getWindow() {
        return window;
    }
}
