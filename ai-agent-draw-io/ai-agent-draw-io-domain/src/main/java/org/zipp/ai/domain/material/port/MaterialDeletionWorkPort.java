package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;
import org.zipp.ai.domain.material.model.valobj.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable deletion workflow and tombstone persistence boundary. */
public interface MaterialDeletionWorkPort {
    Optional<MaterialDeletionLease> claim(String workerId, Instant now, Duration leaseDuration);
    boolean hasActiveReadLeases(String materialId, Instant now);
    List<MaterialVectorLocation> findVectorLocations(String materialId);
    List<MaterialObjectVersion> findObjectVersions(String materialId);
    boolean prepareAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                           long fenceToken, Instant preparedAt);
    boolean recordVectorsAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                 long fenceToken, MaterialDeletionReceipt receipt, Instant deletedAt);
    boolean recordObjectsAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                 long fenceToken, MaterialDeletionReceipt receipt, Instant deletedAt);
    boolean purgeAndComplete(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                             long fenceToken, Instant deletedAt);
    boolean saveRetry(MaterialDeletionTask changed, MaterialDeletionStage expectedStage, long fenceToken);
    int requeueExpiredLeases(Instant now, int limit);
}
