package org.zipp.ai.domain.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Combines live provider utilization with the authoritative monthly indexed-page budget. */
public final class CombinedMaterialCapacitySnapshotPort implements MaterialCapacitySnapshotPort {
    private final MaterialOperationsSnapshotPort operations;
    private final MaterialProviderCapacityFeed providers;
    private final long monthlyPageLimit;
    private final Duration maximumProviderAge;
    private final Clock clock;

    public CombinedMaterialCapacitySnapshotPort(MaterialOperationsSnapshotPort operations,
                                                MaterialProviderCapacityFeed providers,
                                                long monthlyPageLimit,
                                                Duration maximumProviderAge,
                                                Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.providers = Objects.requireNonNull(providers, "providers");
        if (monthlyPageLimit < 1) throw new IllegalArgumentException("monthlyPageLimit must be positive");
        if (maximumProviderAge == null || maximumProviderAge.isZero() || maximumProviderAge.isNegative()) {
            throw new IllegalArgumentException("maximumProviderAge must be positive");
        }
        this.monthlyPageLimit = monthlyPageLimit;
        this.maximumProviderAge = maximumProviderAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MaterialCapacitySnapshot current() {
        Instant now = clock.instant();
        MaterialProviderCapacitySnapshot provider = providers.current();
        boolean fresh = !provider.capturedAt().isAfter(now)
                && provider.capturedAt().plus(maximumProviderAge).isAfter(now);
        double pagesPercent = Math.min(100D,
                operations.current().indexedPagesThisMonth() * 100D / monthlyPageLimit);
        return new MaterialCapacitySnapshot(provider.embeddingPercent(), pagesPercent,
                provider.vectorReadPercent(), provider.vectorWritePercent(),
                fresh && provider.dependenciesAvailable());
    }
}
