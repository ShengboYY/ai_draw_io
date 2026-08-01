package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoMemoryMaintenanceServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void delegatesOneBoundedBatchWithAnExclusiveRetentionCutoff() {
        RecordingPort port = new RecordingPort(7);
        AutoMemoryMaintenanceService service = new AutoMemoryMaintenanceService(
                port,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(90));

        assertEquals(7, service.purgeStaleObserved(25));
        assertEquals(NOW.minus(Duration.ofDays(90)), port.cutoffExclusive);
        assertEquals(25, port.limit);
    }

    @Test
    void rejectsDangerouslyShortRetentionAndUnboundedBatches() {
        RecordingPort port = new RecordingPort(0);
        assertThrows(IllegalArgumentException.class, () -> new AutoMemoryMaintenanceService(
                port,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(29)));

        AutoMemoryMaintenanceService service = new AutoMemoryMaintenanceService(
                port,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(90));
        assertThrows(IllegalArgumentException.class, () -> service.purgeStaleObserved(0));
        assertThrows(IllegalArgumentException.class, () -> service.purgeStaleObserved(1_001));
    }

    @Test
    void rejectsAnImpossiblePersistenceResult() {
        AutoMemoryMaintenanceService service = new AutoMemoryMaintenanceService(
                new RecordingPort(11),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(90));

        assertThrows(IllegalStateException.class, () -> service.purgeStaleObserved(10));
    }

    private static final class RecordingPort implements AutoMemoryMaintenancePort {
        private final int result;
        private Instant cutoffExclusive;
        private int limit;

        private RecordingPort(int result) {
            this.result = result;
        }

        @Override
        public int purgeStaleObserved(Instant cutoffExclusive, int limit) {
            this.cutoffExclusive = cutoffExclusive;
            this.limit = limit;
            return result;
        }
    }
}
