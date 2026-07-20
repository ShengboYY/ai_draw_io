package org.zipp.ai.domain.operations;

import java.util.Map;
import java.util.Objects;

public record MaterialCapabilityReport(Map<MaterialCapability, CapabilityState> capabilities,
                                       CapabilityState overallMaterialState,
                                       CapacityLevel capacityLevel,
                                       double capacityUsagePercent,
                                       MaterialOperationalSnapshot operations) {
    public MaterialCapabilityReport {
        capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        overallMaterialState = Objects.requireNonNull(overallMaterialState, "overallMaterialState");
        capacityLevel = Objects.requireNonNull(capacityLevel, "capacityLevel");
        if (!Double.isFinite(capacityUsagePercent) || capacityUsagePercent < 0D
                || capacityUsagePercent > 100D) {
            throw new IllegalArgumentException("capacityUsagePercent must be between 0 and 100");
        }
        operations = Objects.requireNonNull(operations, "operations");
    }

    public CapabilityState capability(MaterialCapability capability) {
        return capabilities.getOrDefault(capability, CapabilityState.DISABLED);
    }
}
