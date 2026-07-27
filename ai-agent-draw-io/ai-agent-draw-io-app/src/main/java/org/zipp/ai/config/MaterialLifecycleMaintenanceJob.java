package org.zipp.ai.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;

/** Small bounded batches keep TTL and read-lease maintenance safe across multiple app replicas. */
public final class MaterialLifecycleMaintenanceJob {
    private final MaterialLifecycleService lifecycle;
    private final MaterialReadLeaseService leases;
    private final int batchSize;

    public MaterialLifecycleMaintenanceJob(MaterialLifecycleService lifecycle,
                                           MaterialReadLeaseService leases, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize is invalid");
        this.lifecycle = lifecycle;
        this.leases = leases;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.material-lifecycle.maintenance-delay-ms:60000}")
    public void maintain() {
        leases.expireDue(batchSize);
        lifecycle.expireTemporary(batchSize);
        lifecycle.expireTrash(batchSize);
    }
}
