package org.zipp.ai.domain.operations;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CombinedMaterialCapacitySnapshotPortTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void combinesMonthlyPagesWithFreshProviderUsage() {
        MaterialOperationalSnapshot operations = new MaterialOperationalSnapshot(NOW, 0, 0, 0,
                0, 0, 0, 0, 0, 3500, 0, 0, 0, 0);
        MaterialProviderCapacityFeed feed = fixed(
                new MaterialProviderCapacitySnapshot(NOW.minusSeconds(10), 10, 40, 72, 20, true));
        CombinedMaterialCapacitySnapshotPort port = new CombinedMaterialCapacitySnapshotPort(
                () -> operations, feed, 7000, Duration.ofMinutes(3), Clock.fixed(NOW, ZoneOffset.UTC));

        MaterialCapacitySnapshot result = port.current();

        assertEquals(72D, result.maximumUsagePercent());
        assertEquals(50D, result.indexedPagesPercent());
    }

    @Test
    void staleProviderSampleFailsClosed() {
        MaterialProviderCapacityFeed feed = fixed(new MaterialProviderCapacitySnapshot(
                NOW.minusSeconds(181), 10, 1, 1, 1, true));
        CombinedMaterialCapacitySnapshotPort port = new CombinedMaterialCapacitySnapshotPort(
                () -> MaterialOperationalSnapshot.unavailable(NOW), feed, 7000,
                Duration.ofMinutes(3), Clock.fixed(NOW, ZoneOffset.UTC));

        assertFalse(port.current().dependenciesAvailable());
    }

    private MaterialProviderCapacityFeed fixed(MaterialProviderCapacitySnapshot snapshot) {
        return new MaterialProviderCapacityFeed() {
            @Override public MaterialProviderCapacitySnapshot current() { return snapshot; }
            @Override public boolean update(MaterialProviderCapacitySnapshot ignored) { return true; }
        };
    }
}
