package org.zipp.ai.application.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable outbox and lease boundary for rebuildable Memory vector projection. */
public interface AutoMemoryVectorProjectionWorkPort {
    AutoMemoryVectorProjectionWorkPort NOOP = new AutoMemoryVectorProjectionWorkPort() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void enqueue(String memoryId) {
        }

        @Override
        public Optional<AutoMemoryVectorProjectionLease> claim(
                String workerId, Instant now, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean complete(AutoMemoryVectorProjectionLease lease, Instant now) {
            return false;
        }

        @Override
        public boolean retry(
                AutoMemoryVectorProjectionLease lease,
                String errorCode,
                Instant availableAt,
                Instant now
        ) {
            return false;
        }
    };

    default boolean enabled() {
        return true;
    }

    /** Enqueues an authority change, or a reconciliation after a stale provider writer. */
    void enqueue(String memoryId);

    Optional<AutoMemoryVectorProjectionLease> claim(
            String workerId, Instant now, Duration leaseDuration);

    boolean complete(AutoMemoryVectorProjectionLease lease, Instant now);

    boolean retry(
            AutoMemoryVectorProjectionLease lease,
            String errorCode,
            Instant availableAt,
            Instant now);
}
