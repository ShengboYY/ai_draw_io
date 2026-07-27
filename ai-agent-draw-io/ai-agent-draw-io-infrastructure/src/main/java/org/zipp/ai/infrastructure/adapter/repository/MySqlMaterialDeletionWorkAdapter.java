package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialDeletionWorkPort;
import org.zipp.ai.infrastructure.dao.material.IMaterialDeletionMapper;
import org.zipp.ai.infrastructure.dao.material.po.DeletionTaskPO;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL deletion workflow adapter with task fencing and irreversible lifecycle gates. */
@Repository
public class MySqlMaterialDeletionWorkAdapter implements MaterialDeletionWorkPort {
    private final IMaterialDeletionMapper mapper;
    private final MaterialDeletionDatabasePurger purger;

    public MySqlMaterialDeletionWorkAdapter(IMaterialDeletionMapper mapper,
                                             MaterialDeletionDatabasePurger purger) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.purger = Objects.requireNonNull(purger, "purger");
    }

    @Override
    @Transactional
    public Optional<MaterialDeletionLease> claim(String workerId, Instant now, Duration leaseDuration) {
        DeletionTaskPO po = mapper.selectClaimableForUpdate(now);
        if (po == null) return Optional.empty();
        MaterialDeletionTask task = toDomain(po);
        long expectedFence = task.fenceToken();
        task.claim(workerId, now, leaseDuration);
        if (mapper.claimTask(toPo(task), expectedFence) != 1) return Optional.empty();
        return Optional.of(new MaterialDeletionLease(task, task.fenceToken()));
    }

    @Override
    public boolean hasActiveReadLeases(String materialId, Instant now) {
        return mapper.countActiveReadLeases(materialId, now) > 0;
    }

    @Override
    public List<MaterialVectorLocation> findVectorLocations(String materialId) {
        return mapper.selectVectorLocations(materialId).stream().map(po ->
                new MaterialVectorLocation(po.getIndexName(), po.getNamespace(), po.getVectorId())).toList();
    }

    @Override
    public List<MaterialObjectVersion> findObjectVersions(String materialId) {
        return mapper.selectObjectVersions(materialId).stream().map(po ->
                new MaterialObjectVersion(po.getBucket(), po.getObjectKey(), po.getObjectVersionId()))
                .distinct().toList();
    }

    @Override
    @Transactional
    public boolean prepareAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                  long fenceToken, Instant preparedAt) {
        requireFence(changed, expectedStage, fenceToken);
        if (mapper.lockDeletionMaterial(changed.materialId(), changed.lifecycleGeneration()) == null
                || mapper.beginDeleting(changed.materialId(), changed.lifecycleGeneration()) != 1) {
            return false;
        }
        mapper.insertDeletedSourceTombstones(changed.materialId(), preparedAt);
        int tombstones = mapper.insertCitationSourceTombstones(changed.materialId(), preparedAt);
        mapper.deleteMaterialCitationEvidence(changed.materialId());
        mapper.updateTombstonedCitationStates(changed.materialId());
        mapper.markExclusiveBlobsDeletePending(changed.materialId());
        mapper.upsertDeletionPreparationProof(changed.materialId(), changed.lifecycleGeneration(), tombstones);
        saveRequired(changed, expectedStage, fenceToken);
        return true;
    }

    @Override
    @Transactional
    public boolean recordVectorsAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                        long fenceToken, MaterialDeletionReceipt receipt, Instant deletedAt) {
        requireFence(changed, expectedStage, fenceToken);
        if (receipt.providerScope() != null) {
            mapper.markVectorScopeDeleted(changed.materialId(), receipt.providerScope(), deletedAt);
        }
        if (mapper.updateVectorDeletionProof(changed.materialId(), receipt.deletedCount(),
                receipt.providerRequestIdsHash(), deletedAt) != 1) {
            throw new IllegalStateException("vector deletion proof is unavailable");
        }
        saveRequired(changed, expectedStage, fenceToken);
        return true;
    }

    @Override
    @Transactional
    public boolean recordObjectsAndSave(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                        long fenceToken, MaterialDeletionReceipt receipt, Instant deletedAt) {
        requireFence(changed, expectedStage, fenceToken);
        if (mapper.updateObjectDeletionProof(changed.materialId(), receipt.deletedCount(),
                receipt.providerRequestIdsHash(), deletedAt) != 1) {
            throw new IllegalStateException("object deletion proof is unavailable");
        }
        saveRequired(changed, expectedStage, fenceToken);
        return true;
    }

    @Override
    @Transactional
    public boolean purgeAndComplete(MaterialDeletionTask changed, MaterialDeletionStage expectedStage,
                                    long fenceToken, Instant deletedAt) {
        requireFence(changed, expectedStage, fenceToken);
        purger.purge(changed.materialId());
        if (mapper.sanitizeDeletedMaterial(changed.materialId(), changed.lifecycleGeneration(), deletedAt) != 1
                || mapper.completeDeletionProof(changed.materialId(), deletedAt) != 1) {
            return false;
        }
        saveRequired(changed, expectedStage, fenceToken);
        return true;
    }

    @Override
    public boolean saveRetry(MaterialDeletionTask changed, MaterialDeletionStage expectedStage, long fenceToken) {
        return save(changed, expectedStage, fenceToken);
    }

    @Override
    public int requeueExpiredLeases(Instant now, int limit) {
        return mapper.requeueExpiredLeases(now, limit);
    }

    private boolean save(MaterialDeletionTask task, MaterialDeletionStage expectedStage, long expectedFence) {
        return mapper.saveTaskTransition(toPo(task), expectedStage.name(), expectedFence) == 1;
    }

    private void requireFence(MaterialDeletionTask task, MaterialDeletionStage stage, long fenceToken) {
        if (mapper.lockRunningTask(task.id(), stage.name(), fenceToken) == null) {
            throw new IllegalStateException("deletion task fence is no longer current");
        }
    }

    private void saveRequired(MaterialDeletionTask task, MaterialDeletionStage stage, long fenceToken) {
        if (!save(task, stage, fenceToken)) {
            throw new IllegalStateException("deletion task transition lost its fence");
        }
    }

    private MaterialDeletionTask toDomain(DeletionTaskPO po) {
        return MaterialDeletionTask.rehydrate(po.getId(), po.getMaterialId(), po.getLifecycleGeneration(),
                MaterialDeletionStage.valueOf(po.getStage()), MaterialDeletionTaskStatus.valueOf(po.getStatus()),
                po.getAttempt(), po.getNotBefore(), po.getLeaseOwner(), po.getLeaseUntil(),
                po.getFenceToken(), po.getErrorCode());
    }

    private DeletionTaskPO toPo(MaterialDeletionTask task) {
        DeletionTaskPO po = new DeletionTaskPO();
        po.setId(task.id());
        po.setMaterialId(task.materialId());
        po.setLifecycleGeneration(task.lifecycleGeneration());
        po.setStage(task.stage().name());
        po.setStatus(task.status().name());
        po.setAttempt(task.attempt());
        po.setNotBefore(task.notBefore());
        po.setLeaseOwner(task.leaseOwner());
        po.setLeaseUntil(task.leaseUntil());
        po.setFenceToken(task.fenceToken());
        po.setErrorCode(task.errorCode());
        return po;
    }

}
