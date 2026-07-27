package org.zipp.ai.domain.operations;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Computes a fail-closed material capability view without coupling ordinary drawing to RAG dependencies. */
public final class MaterialCapabilityService {
    private static final long QUEUE_AGE_ALERT_SECONDS = 120;
    private final MaterialOperationsSnapshotPort operations;
    private final MaterialCapacityBreaker capacityBreaker;

    public MaterialCapabilityService(MaterialOperationsSnapshotPort operations,
                                     MaterialCapacityBreaker capacityBreaker) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.capacityBreaker = Objects.requireNonNull(capacityBreaker, "capacityBreaker");
    }

    public MaterialCapabilityReport assess(MaterialFeatureSet featureSet, boolean releaseApproved) {
        MaterialFeatureSet flags = Objects.requireNonNull(featureSet, "featureSet");
        OperationalStatus operational = currentOperations();
        MaterialOperationalSnapshot snapshot = operational.snapshot();
        CapacityStatus capacityStatus = capacityBreaker.currentStatus();
        CapacityLevel capacity = capacityStatus.level();
        EnumMap<MaterialCapability, CapabilityState> states = new EnumMap<>(MaterialCapability.class);
        states.put(MaterialCapability.PLAIN_TEXT_DRAWING, CapabilityState.AVAILABLE);
        states.put(MaterialCapability.MATERIAL_INGESTION, enabled(flags.ingestionEnabled()));
        states.put(MaterialCapability.MATERIAL_UPLOAD,
                flags.uploadEnabled() && flags.ingestionEnabled()
                        ? CapabilityState.AVAILABLE : dependencyState(flags.uploadEnabled()));
        states.put(MaterialCapability.LIBRARY,
                flags.libraryEnabled() && flags.ingestionEnabled()
                        ? CapabilityState.AVAILABLE : dependencyState(flags.libraryEnabled()));
        states.put(MaterialCapability.RETRIEVAL, retrievalState(flags));
        states.put(MaterialCapability.CITATION_COMMIT,
                dependent(flags.citationCommitEnabled(), states.get(MaterialCapability.RETRIEVAL)));
        states.put(MaterialCapability.EVIDENCE_ANSWER,
                dependent(flags.evidenceAnswerEnabled(), states.get(MaterialCapability.RETRIEVAL)));
        states.put(MaterialCapability.ANONYMOUS_UPLOAD,
                anonymousState(flags, releaseApproved, capacity, states));
        return new MaterialCapabilityReport(Map.copyOf(states), overall(flags, operational.available(),
                        snapshot, capacity),
                capacity, capacityStatus.maximumUsagePercent(), snapshot);
    }

    private CapabilityState retrievalState(MaterialFeatureSet flags) {
        if (!flags.retrievalEnabled()) {
            if (!flags.retrievalShadowEnabled()) return CapabilityState.DISABLED;
            return flags.ingestionEnabled() && flags.libraryEnabled() && flags.lifecycleEnabled()
                    ? CapabilityState.SHADOW : CapabilityState.MISCONFIGURED;
        }
        return flags.ingestionEnabled() && flags.libraryEnabled() && flags.lifecycleEnabled()
                ? CapabilityState.AVAILABLE : CapabilityState.MISCONFIGURED;
    }

    private CapabilityState anonymousState(MaterialFeatureSet flags, boolean releaseApproved,
                                           CapacityLevel capacity,
                                           Map<MaterialCapability, CapabilityState> states) {
        if (!flags.anonymousUploadEnabled()) return CapabilityState.DISABLED;
        boolean priorStagesAvailable = states.get(MaterialCapability.MATERIAL_UPLOAD) == CapabilityState.AVAILABLE
                && states.get(MaterialCapability.LIBRARY) == CapabilityState.AVAILABLE
                && states.get(MaterialCapability.RETRIEVAL) == CapabilityState.AVAILABLE
                && states.get(MaterialCapability.CITATION_COMMIT) == CapabilityState.AVAILABLE
                && states.get(MaterialCapability.EVIDENCE_ANSWER) == CapabilityState.AVAILABLE;
        return priorStagesAvailable && releaseApproved
                && capacity != CapacityLevel.EXHAUSTED && capacity != CapacityLevel.UNAVAILABLE
                ? CapabilityState.AVAILABLE : CapabilityState.BLOCKED;
    }

    private CapabilityState overall(MaterialFeatureSet flags, boolean operationsAvailable,
                                    MaterialOperationalSnapshot snapshot,
                                    CapacityLevel capacity) {
        if (!flags.ingestionEnabled() && !flags.uploadEnabled() && !flags.libraryEnabled()
                && !flags.retrievalEnabled()) return CapabilityState.DISABLED;
        if (!operationsAvailable || snapshot.oldestQueuedSeconds() > QUEUE_AGE_ALERT_SECONDS
                || snapshot.stuckDeletingMaterials() > 0 || snapshot.expiredReadLeases() > 0
                || snapshot.staleVectorBatches() > 0 || snapshot.openProjectionRepairs() > 0
                || snapshot.pendingOrphanDeletions() > 0 || snapshot.purgingGenerations() > 0
                || capacity == CapacityLevel.WARNING || capacity == CapacityLevel.RESTRICTED
                || capacity == CapacityLevel.EXHAUSTED || capacity == CapacityLevel.UNAVAILABLE) {
            return CapabilityState.DEGRADED;
        }
        return CapabilityState.AVAILABLE;
    }

    private OperationalStatus currentOperations() {
        try {
            return new OperationalStatus(
                    Objects.requireNonNull(operations.current(), "operations snapshot"), true);
        } catch (RuntimeException exception) {
            // The dashboard remains queryable, but a missing operational projection is never healthy.
            return new OperationalStatus(MaterialOperationalSnapshot.unavailable(Instant.EPOCH), false);
        }
    }

    private record OperationalStatus(MaterialOperationalSnapshot snapshot, boolean available) { }

    private CapabilityState enabled(boolean enabled) {
        return enabled ? CapabilityState.AVAILABLE : CapabilityState.DISABLED;
    }

    private CapabilityState dependencyState(boolean enabled) {
        return enabled ? CapabilityState.MISCONFIGURED : CapabilityState.DISABLED;
    }

    private CapabilityState dependent(boolean enabled, CapabilityState dependency) {
        if (!enabled) return CapabilityState.DISABLED;
        return dependency == CapabilityState.AVAILABLE
                ? CapabilityState.AVAILABLE : CapabilityState.MISCONFIGURED;
    }
}
