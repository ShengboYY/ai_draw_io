package org.zipp.ai.domain.operations;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

import java.util.Objects;

/** Protects anonymous capacity while keeping registered and plain-text paths independently available. */
public final class MaterialCapacityBreaker {
    private final MaterialCapacitySnapshotPort snapshots;

    public MaterialCapacityBreaker(MaterialCapacitySnapshotPort snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public CapacityDecision decide(OwnerType ownerType, CapacityWorkload workload) {
        OwnerType owner = Objects.requireNonNull(ownerType, "ownerType");
        CapacityWorkload work = Objects.requireNonNull(workload, "workload");
        if (work == CapacityWorkload.PLAIN_TEXT_DRAWING) {
            return new CapacityDecision(true, CapacityLevel.HEALTHY, "PLAIN_TEXT_INDEPENDENT");
        }
        MaterialCapacitySnapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(snapshots.current(), "capacity snapshot");
        } catch (RuntimeException exception) {
            return new CapacityDecision(owner != OwnerType.ANONYMOUS, CapacityLevel.UNAVAILABLE,
                    "CAPACITY_STATUS_UNAVAILABLE");
        }
        CapacityLevel level = level(snapshot);
        if (owner != OwnerType.ANONYMOUS) {
            // Registered work stays visible and queueable; callers can surface the degraded level.
            return new CapacityDecision(true, level, "REGISTERED_CAPACITY_VISIBLE");
        }
        if (level == CapacityLevel.UNAVAILABLE) {
            return new CapacityDecision(false, level, "CAPACITY_STATUS_UNAVAILABLE");
        }
        if (level == CapacityLevel.EXHAUSTED && work == CapacityWorkload.NEW_UPLOAD) {
            return new CapacityDecision(false, level, "ANONYMOUS_CAPACITY_EXHAUSTED");
        }
        if ((level == CapacityLevel.RESTRICTED || level == CapacityLevel.EXHAUSTED)
                && work == CapacityWorkload.REPROCESS) {
            return new CapacityDecision(false, level, "ANONYMOUS_LOW_PRIORITY_RESTRICTED");
        }
        return new CapacityDecision(true, level, "CAPACITY_AVAILABLE");
    }

    public CapacityLevel currentLevel() {
        return currentStatus().level();
    }

    public CapacityStatus currentStatus() {
        try {
            MaterialCapacitySnapshot snapshot = Objects.requireNonNull(snapshots.current(), "capacity snapshot");
            return new CapacityStatus(level(snapshot), snapshot.maximumUsagePercent());
        } catch (RuntimeException exception) {
            return new CapacityStatus(CapacityLevel.UNAVAILABLE, 0D);
        }
    }

    private CapacityLevel level(MaterialCapacitySnapshot snapshot) {
        if (!snapshot.dependenciesAvailable()) return CapacityLevel.UNAVAILABLE;
        double maximum = snapshot.maximumUsagePercent();
        if (maximum >= 95D) return CapacityLevel.EXHAUSTED;
        if (maximum >= 85D) return CapacityLevel.RESTRICTED;
        if (maximum >= 70D) return CapacityLevel.WARNING;
        return CapacityLevel.HEALTHY;
    }
}
