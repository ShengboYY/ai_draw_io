package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemoryMaintenanceService;
import org.zipp.ai.config.AutoMemoryMaintenanceJob;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoMemoryMaintenanceJobTest {
    @Test
    void runsExactlyOneConfiguredBatchPerTick() {
        int[] observedLimit = {0};
        AutoMemoryMaintenanceService service = new AutoMemoryMaintenanceService(
                (cutoff, limit) -> {
                    observedLimit[0] = limit;
                    return 0;
                },
                Clock.systemUTC(),
                Duration.ofDays(90));

        new AutoMemoryMaintenanceJob(service, 75).maintain();

        assertEquals(75, observedLimit[0]);
    }

    @Test
    void rejectsAnUnboundedBatchAtCompositionTime() {
        AutoMemoryMaintenanceService service = new AutoMemoryMaintenanceService(
                (cutoff, limit) -> 0,
                Clock.systemUTC(),
                Duration.ofDays(90));

        assertThrows(IllegalArgumentException.class,
                () -> new AutoMemoryMaintenanceJob(service, 1_001));
    }
}
