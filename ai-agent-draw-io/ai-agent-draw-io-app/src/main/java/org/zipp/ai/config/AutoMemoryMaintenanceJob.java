package org.zipp.ai.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.application.memory.AutoMemoryMaintenanceService;

import java.util.Objects;

/** Runs one bounded aging batch per tick so maintenance never monopolizes the Memory table. */
public final class AutoMemoryMaintenanceJob {
    private final AutoMemoryMaintenanceService maintenance;
    private final int batchSize;

    public AutoMemoryMaintenanceJob(
            AutoMemoryMaintenanceService maintenance,
            int batchSize
    ) {
        if (batchSize < 1 || batchSize > AutoMemoryMaintenanceService.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.memory.aging-delay-ms:3600000}")
    public void maintain() {
        maintenance.purgeStaleObserved(batchSize);
    }
}
