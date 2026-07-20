package org.zipp.ai.domain.retrieval.internal;

import java.time.Duration;

/** One monotonic request deadline shared by retrieval lanes and evidence hydration. */
final class RetrievalDeadline {
    private final long expiresAtNanos;

    private RetrievalDeadline(Duration budget) {
        long nanos = Math.max(1L, budget.toNanos());
        this.expiresAtNanos = System.nanoTime() + nanos;
    }

    static RetrievalDeadline start(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("retrieval budget must be positive");
        }
        return new RetrievalDeadline(budget);
    }

    long remainingNanos() {
        return Math.max(0L, expiresAtNanos - System.nanoTime());
    }

    long boundedNanos(Duration stageBudget) {
        return Math.min(remainingNanos(), Math.max(1L, stageBudget.toNanos()));
    }
}
