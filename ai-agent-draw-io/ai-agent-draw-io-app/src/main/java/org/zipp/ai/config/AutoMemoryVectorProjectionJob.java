package org.zipp.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorker;

import java.util.UUID;

/** Drains a bounded number of opt-in Memory vector projection items per scheduler tick. */
public final class AutoMemoryVectorProjectionJob {
    private final AutoMemoryVectorProjectionWorker worker;
    private final String workerId = "auto-memory-vector-" + UUID.randomUUID();

    @Value("${app.memory.vector.worker-batch-size:10}")
    private int batchSize;

    public AutoMemoryVectorProjectionJob(AutoMemoryVectorProjectionWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.memory.vector.worker-delay-ms:5000}")
    public void project() {
        int boundedBatchSize = Math.max(1, Math.min(100, batchSize));
        for (int index = 0; index < boundedBatchSize; index++) {
            if (!worker.runOnce(workerId)) {
                return;
            }
        }
    }
}
