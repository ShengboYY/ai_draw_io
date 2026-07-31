package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable outbox/lease boundary for post-commit automatic extraction. */
public interface AutoMemoryExtractionWorkPort {
    AutoMemoryExtractionWorkPort NOOP = new AutoMemoryExtractionWorkPort() {
        @Override
        public void enqueue(TurnKey turn, String diagramId) {
        }

        @Override
        public Optional<AutoMemoryExtractionLease> claim(
                String workerId, Instant now, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean complete(AutoMemoryExtractionLease lease, Instant now) {
            return false;
        }

        @Override
        public boolean retry(
                AutoMemoryExtractionLease lease, String errorCode, Instant availableAt, Instant now) {
            return false;
        }
    };

    /** Called inside the successful terminal transaction. */
    void enqueue(TurnKey turn, String diagramId);

    Optional<AutoMemoryExtractionLease> claim(
            String workerId, Instant now, Duration leaseDuration);

    boolean complete(AutoMemoryExtractionLease lease, Instant now);

    boolean retry(
            AutoMemoryExtractionLease lease,
            String errorCode,
            Instant availableAt,
            Instant now);
}
