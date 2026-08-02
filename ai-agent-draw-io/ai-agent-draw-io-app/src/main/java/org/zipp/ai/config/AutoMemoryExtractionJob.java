package org.zipp.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorker;

import java.util.UUID;

/** Drains a bounded number of durable extraction items on each scheduler tick. */
public final class AutoMemoryExtractionJob {
    private final AutoMemoryExtractionWorker worker;
    private final String workerId = "auto-memory-" + UUID.randomUUID();

    @Value("${app.memory.worker-batch-size:10}")
    private int batchSize;

    public AutoMemoryExtractionJob(AutoMemoryExtractionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.memory.worker-delay-ms:5000}")
    public void extract() {
        int boundedBatchSize = Math.max(1, Math.min(100, batchSize));
        for (int index = 0; index < boundedBatchSize; index++) {
            if (!worker.runOnce(workerId)) {
                return;
            }
        }
    }
}
