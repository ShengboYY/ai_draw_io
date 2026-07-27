package org.zipp.ai.trigger.http.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.operations.MaterialCapabilityService;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialCapabilityTelemetry;
import org.zipp.ai.domain.operations.MaterialReleaseApproval;

import java.util.Objects;

/** Periodically refreshes bounded Prometheus gauges without querying MySQL during metric scrapes. */
public final class MaterialOperationsMonitor {
    private final MaterialCapabilityService capabilities;
    private final MaterialFeatureSet featureSet;
    private final MaterialReleaseApproval releaseApproval;
    private final MaterialCapabilityTelemetry metrics;

    public MaterialOperationsMonitor(MaterialCapabilityService capabilities,
                                     MaterialFeatureSet featureSet,
                                     MaterialReleaseApproval releaseApproval,
                                     MaterialCapabilityTelemetry metrics) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.featureSet = Objects.requireNonNull(featureSet, "featureSet");
        this.releaseApproval = Objects.requireNonNull(releaseApproval, "releaseApproval");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Scheduled(fixedDelayString = "${app.material-operations.refresh-ms:30000}")
    public void refresh() {
        metrics.publish(capabilities.assess(featureSet, releaseApproval.releasable()));
    }
}
