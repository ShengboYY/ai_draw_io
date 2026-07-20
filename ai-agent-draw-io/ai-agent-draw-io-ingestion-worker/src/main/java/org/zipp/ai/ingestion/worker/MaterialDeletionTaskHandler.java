package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;
import org.zipp.ai.domain.material.port.MaterialDeletionVectorPort;
import org.zipp.ai.domain.material.port.MaterialDeletionWorkPort;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Executes one durable deletion stage; every external operation is idempotent by exact identity. */
public final class MaterialDeletionTaskHandler {
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final MaterialDeletionWorkPort work;
    private final MaterialDeletionVectorPort vectors;
    private final MaterialDeletionObjectPort objects;
    private final Clock clock;

    public MaterialDeletionTaskHandler(MaterialDeletionWorkPort work,
                                       MaterialDeletionVectorPort vectors,
                                       MaterialDeletionObjectPort objects,
                                       Clock clock) {
        this.work = Objects.requireNonNull(work, "work");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void handle(MaterialDeletionLease lease) {
        MaterialDeletionTask task = lease.task();
        MaterialDeletionStage expectedStage = task.stage();
        try {
            switch (expectedStage) {
                case WAIT_LEASES -> waitForLeases(lease);
                case DELETE_VECTORS -> deleteVectors(lease);
                case DELETE_OBJECTS -> deleteObjects(lease);
                case PURGE_DATABASE -> purgeDatabase(lease);
            }
        } catch (RuntimeException error) {
            // A lost fence is left for lease reaping; only the current running aggregate can retry.
            if (task.status() == MaterialDeletionTaskStatus.RUNNING) {
                task.defer("DELETION_STAGE_FAILED", clock.instant().plus(RETRY_DELAY));
                work.saveRetry(task, expectedStage, lease.fenceToken());
            }
        }
    }

    private void waitForLeases(MaterialDeletionLease lease) {
        MaterialDeletionTask task = lease.task();
        if (work.hasActiveReadLeases(task.materialId(), clock.instant())) {
            task.defer("ACTIVE_READ_LEASE", clock.instant().plus(RETRY_DELAY));
            work.saveRetry(task, MaterialDeletionStage.WAIT_LEASES, lease.fenceToken());
            return;
        }
        task.advance(MaterialDeletionStage.DELETE_VECTORS, clock.instant());
        work.prepareAndSave(task, MaterialDeletionStage.WAIT_LEASES, lease.fenceToken(), clock.instant());
    }

    private void deleteVectors(MaterialDeletionLease lease) {
        MaterialDeletionTask task = lease.task();
        var locations = work.findVectorLocations(task.materialId());
        MaterialDeletionReceipt receipt = vectors.delete(locations);
        if (receipt.allRequestedHandled()) {
            task.advance(MaterialDeletionStage.DELETE_OBJECTS, clock.instant());
        } else {
            task.defer("VECTOR_PROFILE_REQUIRED", clock.instant().plus(RETRY_DELAY));
        }
        work.recordVectorsAndSave(task, MaterialDeletionStage.DELETE_VECTORS,
                lease.fenceToken(), receipt, clock.instant());
    }

    private void deleteObjects(MaterialDeletionLease lease) {
        MaterialDeletionTask task = lease.task();
        var versions = work.findObjectVersions(task.materialId());
        MaterialDeletionReceipt receipt = objects.delete(versions);
        task.advance(MaterialDeletionStage.PURGE_DATABASE, clock.instant());
        work.recordObjectsAndSave(task, MaterialDeletionStage.DELETE_OBJECTS,
                lease.fenceToken(), receipt, clock.instant());
    }

    private void purgeDatabase(MaterialDeletionLease lease) {
        MaterialDeletionTask task = lease.task();
        task.complete();
        work.purgeAndComplete(task, MaterialDeletionStage.PURGE_DATABASE,
                lease.fenceToken(), clock.instant());
    }
}
