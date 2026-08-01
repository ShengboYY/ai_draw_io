package org.zipp.ai.application.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Conservatively ages only unconfirmed inferred observations. ACTIVE, explicit and DISABLED
 * Memory remain user-controlled until the product has a reliable usage or contradiction signal.
 */
public final class AutoMemoryMaintenanceService {
    public static final Duration MINIMUM_OBSERVED_RETENTION = Duration.ofDays(30);
    public static final int MAXIMUM_BATCH_SIZE = 1_000;

    private final AutoMemoryMaintenancePort maintenance;
    private final Clock clock;
    private final Duration observedRetention;

    public AutoMemoryMaintenanceService(
            AutoMemoryMaintenancePort maintenance,
            Clock clock,
            Duration observedRetention
    ) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observedRetention = Objects.requireNonNull(observedRetention, "observedRetention");
        if (observedRetention.compareTo(MINIMUM_OBSERVED_RETENTION) < 0) {
            throw new IllegalArgumentException("observedRetention must be at least 30 days");
        }
    }

    public int purgeStaleObserved(int limit) {
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        Instant cutoff = clock.instant().minus(observedRetention);
        int purged = maintenance.purgeStaleObserved(cutoff, limit);
        if (purged < 0 || purged > limit) {
            throw new IllegalStateException("AUTO_MEMORY_MAINTENANCE_RESULT_INVALID");
        }
        return purged;
    }
}
