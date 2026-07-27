package org.zipp.ai.domain.retrieval.internal;

import java.time.Duration;

/** Small process-local breaker used independently for Pinecone inference and data-plane calls. */
final class CallCircuitBreaker {
    private final int failureThreshold;
    private final long openNanos;
    private int consecutiveFailures;
    private long openUntilNanos;
    private boolean halfOpenProbe;

    CallCircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1 || openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("invalid circuit breaker policy");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        if (openUntilNanos == 0L) return true;
        if (now < openUntilNanos || halfOpenProbe) return false;
        halfOpenProbe = true;
        return true;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntilNanos = 0L;
        halfOpenProbe = false;
    }

    synchronized void recordFailure() {
        halfOpenProbe = false;
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntilNanos = System.nanoTime() + openNanos;
        }
    }
}
