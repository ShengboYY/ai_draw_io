package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.IndexProjectionMaintenancePort;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.infrastructure.dao.material.IIndexProjectionMaintenanceMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RagIndexGenerationPO;
import org.zipp.ai.infrastructure.dao.material.po.VectorProjectionWorkPO;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** MySQL boundary for exact-ID projection maintenance and audited operator repair. */
@Repository
public class MySqlIndexProjectionMaintenanceAdapter implements IndexProjectionMaintenancePort {
    private final IIndexProjectionMaintenanceMapper mapper;
    private final IProcessingJobMapper jobMapper;
    private final MySqlVectorProjectionPersistence persistence;

    public MySqlIndexProjectionMaintenanceAdapter(IIndexProjectionMaintenanceMapper mapper,
                                                   IProcessingJobMapper jobMapper,
                                                   IVectorProjectionMapper vectorMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.persistence = new MySqlVectorProjectionPersistence(vectorMapper, jobMapper);
    }

    @Override
    public List<ProjectionBatchInventory> findBatchesDue(String generationId, Instant dueBefore, int limit) {
        String generation = required(generationId, "generationId");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Map<String, List<VectorProjectionWorkPO>> grouped = new LinkedHashMap<>();
        for (VectorProjectionWorkPO row : mapper.selectBatchesDue(
                generation, Objects.requireNonNull(dueBefore, "dueBefore"), limit)) {
            String key = row.getRevisionId() + ":" + row.getBatchNo();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(rows -> {
            VectorProjectionWorkPO first = rows.get(0);
            return new ProjectionBatchInventory(first.getIndexGenerationId(), first.getRevisionId(),
                    first.getBatchNo(), first.getBatchInputFingerprint(),
                    rows.stream().map(VectorProjectionWorkPO::getVectorId).toList());
        }).toList();
    }

    @Override
    public boolean markBatchChecked(ProjectionBatchInventory batch, Instant checkedAt) {
        ProjectionBatchInventory source = Objects.requireNonNull(batch, "batch");
        return mapper.markBatchChecked(source.generationId(), source.revisionId(), source.batchNo(),
                source.batchInputFingerprint(), Objects.requireNonNull(checkedAt, "checkedAt")) == 1;
    }

    @Override
    @Transactional
    public boolean scheduleMissingVectorRepair(ProjectionBatchInventory batch, Set<String> missingVectorIds,
                                               Instant requestedAt) {
        ProjectionBatchInventory source = Objects.requireNonNull(batch, "batch");
        Set<String> missing = Set.copyOf(missingVectorIds);
        if (missing.isEmpty() || !new HashSet<>(source.vectorIds()).containsAll(missing)) {
            throw new IllegalArgumentException("missing vectors must belong to the authoritative batch");
        }
        Instant now = Objects.requireNonNull(requestedAt, "requestedAt");
        mapper.closeTerminalRepair(source.generationId(), source.revisionId(), source.batchNo(), now);
        List<String> orderedMissing = missing.stream().sorted().toList();
        String missingFingerprint = VectorGenerationProfile.sha256(String.join(":", orderedMissing));
        String repairId = "repair_" + VectorGenerationProfile.sha256(source.generationId() + ":"
                + source.revisionId() + ":" + source.batchNo() + ":" + missingFingerprint + ":"
                + now.toEpochMilli()).substring(0, 40);
        String workKey = "ig:" + source.generationId() + ":repair:" + repairId;
        String inputFingerprint = VectorGenerationProfile.sha256("VECTOR_REPAIR_V1:"
                + source.batchInputFingerprint() + ":" + missingFingerprint);
        if (mapper.insertRepairAudit(repairId, source.generationId(), source.revisionId(), source.batchNo(),
                workKey, inputFingerprint, source.batchInputFingerprint(), missingFingerprint,
                missing.size(), now) != 1) {
            return false;
        }
        ProcessingJob job = ProcessingJob.enqueue("job_" + repairId,
                ProcessingJobTarget.forRevision(source.revisionId()), ProcessingJobStage.REPAIR_VECTOR_BATCH,
                workKey, inputFingerprint, 10, now);
        if (jobMapper.insert(persistence.processingJob(job)) != 1) {
            throw new IllegalStateException("vector repair job identity collided");
        }
        return true;
    }

    @Override
    @Transactional
    public Optional<VectorRepairWork> findRepairWork(String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<VectorProjectionWorkPO> rows = mapper.selectRepairWork(required(revisionId, "revisionId"),
                required(workKey, "workKey"), current.jobId(), current.workerId(), current.fenceToken());
        if (rows.isEmpty()) return Optional.empty();
        VectorProjectionWorkPO first = rows.get(0);
        mapper.markRepairRunning(first.getRepairId());
        StoredArtifact artifact = new StoredArtifact(first.getVectorObjectKey(),
                first.getVectorObjectVersionId(), first.getVectorContentSha256(),
                first.getVectorByteSize(), first.getVectorContentType());
        List<VectorProjectionMetadata> projections = rows.stream().map(this::metadata).toList();
        return Optional.of(new VectorRepairWork(first.getRepairId(), persistence.context(first),
                persistence.profile(first), first.getBatchNo(), first.getWorkKey(),
                first.getRepairInputFingerprint(), first.getBatchInputFingerprint(), artifact, projections));
    }

    @Override
    @Transactional
    public boolean completeRepair(VectorRepairWork work, WorkerFence fence, Instant completedAt) {
        VectorRepairWork source = Objects.requireNonNull(work, "work");
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        Instant completed = Objects.requireNonNull(completedAt, "completedAt");
        if (mapper.countRepairFence(source.repairId(), current.jobId(), current.workerId(),
                current.fenceToken()) != 1) return false;
        if (mapper.completeRepair(source.repairId(), completed) != 1) {
            throw new IllegalStateException("vector repair audit lost its fence");
        }
        if (mapper.markBatchChecked(source.profile().generationId(), source.context().revisionId(),
                source.batchNo(), source.batchInputFingerprint(), completed) != 1) {
            throw new IllegalStateException("repaired vector batch identity became stale");
        }
        return true;
    }

    @Override
    @Transactional
    public String findProviderCursor(String generationId, Instant initializedAt) {
        String generation = required(generationId, "generationId");
        mapper.insertProviderCursor(generation, Objects.requireNonNull(initializedAt, "initializedAt"));
        return mapper.selectProviderCursor(generation);
    }

    @Override
    public boolean advanceProviderCursor(String generationId, String expectedCursor, String nextCursor,
                                         Instant updatedAt) {
        return mapper.advanceProviderCursor(required(generationId, "generationId"), normalized(expectedCursor),
                normalized(nextCursor), Objects.requireNonNull(updatedAt, "updatedAt")) == 1;
    }

    @Override
    public Set<String> findKnownVectorIds(List<String> providerVectorIds) {
        List<String> ids = List.copyOf(providerVectorIds);
        if (ids.isEmpty()) return Set.of();
        if (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("provider vector ID page is invalid");
        }
        return Set.copyOf(mapper.selectKnownVectorIds(ids));
    }

    @Override
    public String recordOrphanDeletionIntent(String generationId, List<String> vectorIds, Instant requestedAt) {
        List<String> supplied = Objects.requireNonNull(vectorIds, "vectorIds");
        if (supplied.isEmpty() || supplied.size() > 100
                || supplied.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("orphan vector ID page is invalid");
        }
        List<String> ids = supplied.stream().sorted().toList();
        String fingerprint = VectorGenerationProfile.sha256(String.join(":", ids));
        String generation = required(generationId, "generationId");
        String deletionId = "orphan_" + VectorGenerationProfile.sha256(
                generation + ":" + fingerprint)
                .substring(0, 40);
        mapper.upsertOrphanDeletionIntent(deletionId, generation, fingerprint, ids.size(),
                Objects.requireNonNull(requestedAt, "requestedAt"));
        return deletionId;
    }

    @Override
    public boolean completeOrphanDeletion(String deletionId, Instant completedAt) {
        return mapper.completeOrphanDeletion(required(deletionId, "deletionId"),
                Objects.requireNonNull(completedAt, "completedAt")) == 1;
    }

    @Override
    public String findTemporaryCleanupCursor(String generationId, Instant initializedAt) {
        String generation = required(generationId, "generationId");
        mapper.insertTemporaryCleanupCursor(generation, Objects.requireNonNull(initializedAt, "initializedAt"));
        String cursor = mapper.selectTemporaryCleanupCursor(generation);
        return cursor == null ? "" : cursor;
    }

    @Override
    public boolean advanceTemporaryCleanupCursor(String generationId, String expectedMaterialId,
                                                 String nextMaterialId, Instant updatedAt) {
        return mapper.advanceTemporaryCleanupCursor(required(generationId, "generationId"),
                text(expectedMaterialId), text(nextMaterialId),
                Objects.requireNonNull(updatedAt, "updatedAt")) == 1;
    }

    @Override
    public Optional<TemporaryProjectionCleanup> findTemporaryConversationCleanup(
            String generationId, String afterMaterialId, Instant now, int limit) {
        String generation = required(generationId, "generationId");
        String cursor = afterMaterialId == null ? "" : afterMaterialId.trim();
        Instant current = Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("temporary cleanup limit must be between 1 and 100");
        }
        String materialId = mapper.selectTemporaryCleanupMaterial(generation, cursor, current);
        if (materialId == null && !cursor.isEmpty()) {
            // Wrap the round-robin scan after the highest eligible material ID.
            materialId = mapper.selectTemporaryCleanupMaterial(generation, "", current);
        }
        if (materialId == null) return Optional.empty();
        List<String> vectorIds = mapper.selectTemporaryCleanupVectorIds(
                generation, materialId, current, limit);
        if (vectorIds.isEmpty()) return Optional.empty();
        return Optional.of(new TemporaryProjectionCleanup(generation, materialId, vectorIds));
    }

    @Override
    @Transactional
    public boolean claimTemporaryConversationVectorsForDeletion(
            TemporaryProjectionCleanup cleanup, Instant deletedAt) {
        TemporaryProjectionCleanup source = Objects.requireNonNull(cleanup, "cleanup");
        Instant deleted = Objects.requireNonNull(deletedAt, "deletedAt");
        // The same material row is locked by promotion and new read-lease authorization.
        if (mapper.lockExpiredTemporaryCleanupMaterial(source.materialId(), deleted) == null) {
            return false;
        }
        return mapper.claimTemporaryConversationVectorsForDeletion(
                source.generationId(), source.materialId(), source.vectorIds(),
                deleted) == source.vectorIds().size();
    }

    @Override
    public Optional<PendingProjectionDeletion> findTemporaryProviderDeletionRetry(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("temporary retry limit must be between 1 and 100");
        }
        RagIndexGenerationPO generation = mapper.selectPendingProviderDeletionGeneration();
        if (generation == null) return Optional.empty();
        List<String> ids = mapper.selectTemporaryProviderDeletionRetries(
                required(generation.getId(), "generationId"), limit);
        if (ids.isEmpty()) return Optional.empty();
        return Optional.of(new PendingProjectionDeletion(generation.getId(),
                required(generation.getNamespace(), "namespace"), ids));
    }

    @Override
    public boolean completeTemporaryProviderDeletion(String generationId, List<String> vectorIds,
                                                     Instant completedAt) {
        List<String> ids = exactVectorIds(vectorIds, "temporary retry vector IDs");
        return mapper.completeTemporaryProviderDeletion(
                required(generationId, "generationId"), ids,
                Objects.requireNonNull(completedAt, "completedAt")) == ids.size();
    }

    @Override
    @Transactional
    public Optional<RetiredGenerationCleanup> findRetiredCleanup(
            Instant now, Duration minimumGrace, int limit) {
        Duration grace = Objects.requireNonNull(minimumGrace, "minimumGrace");
        if (grace.isNegative() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("retired cleanup bounds are invalid");
        }
        Instant current = Objects.requireNonNull(now, "now");
        RagIndexGenerationPO candidate = mapper.selectRetiredCleanupGeneration(
                current, grace.toSeconds());
        if (candidate == null) return Optional.empty();
        String generation = required(candidate.getId(), "generationId");
        mapper.claimRetiredGenerationCleanup(generation, current, grace.toSeconds());
        Instant eligibleAt = mapper.selectRetiredEligibleAt(generation, current, grace.toSeconds());
        if (eligibleAt == null) return Optional.empty();
        return Optional.of(new RetiredGenerationCleanup(generation,
                required(candidate.getNamespace(), "namespace"), eligibleAt,
                mapper.selectRetiredVectorIds(generation, limit)));
    }

    @Override
    public boolean markRetiredVectorsDeleted(RetiredGenerationCleanup cleanup, Instant deletedAt) {
        RetiredGenerationCleanup source = Objects.requireNonNull(cleanup, "cleanup");
        if (source.vectorIds().isEmpty()) return false;
        return mapper.markRetiredVectorsDeleted(source.generationId(), source.vectorIds(),
                Objects.requireNonNull(deletedAt, "deletedAt")) == source.vectorIds().size();
    }

    @Override
    public boolean completeRetiredGeneration(String generationId, Instant completedAt) {
        return mapper.completeRetiredGeneration(required(generationId, "generationId"),
                Objects.requireNonNull(completedAt, "completedAt")) == 1;
    }

    @Override
    @Transactional
    public boolean retryFailedTarget(String generationId, String revisionId, String requestedByHash,
                                     String reasonCode, Instant requestedAt) {
        String generation = required(generationId, "generationId");
        String revision = required(revisionId, "revisionId");
        String operator = sha256(requestedByHash, "requestedByHash");
        String reason = required(reasonCode, "reasonCode");
        if (!reason.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
        Instant requested = Objects.requireNonNull(requestedAt, "requestedAt");
        if (mapper.lockTargetState(generation, revision) == null) return false;
        ProcessingJobPO failed = mapper.selectFailedTargetJobForUpdate(generation, revision);
        if (failed == null) return false;
        String repairId = "target_" + VectorGenerationProfile.sha256(generation + ":" + revision + ":"
                + failed.getId() + ":" + requested.toEpochMilli()).substring(0, 40);
        if (mapper.insertTargetRepairAudit(repairId, generation, revision, failed.getId(),
                failed.getLastErrorCode(), operator, reason, requested) != 1
                || mapper.requeueFailedJob(failed.getId(), requested) != 1) {
            throw new IllegalStateException("failed target repair lost its transaction boundary");
        }
        return true;
    }

    private VectorProjectionMetadata metadata(VectorProjectionWorkPO po) {
        return new VectorProjectionMetadata(po.getChunkId(), po.getVectorId(), po.getProjectionFingerprint(),
                RetrievalChunkType.valueOf(po.getChunkType()),
                org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.valueOf(po.getModality()),
                po.getPageNo(), po.getLanguage());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String sha256(String value, String field) {
        String result = required(value, field);
        if (!result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return result;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> exactVectorIds(List<String> vectorIds, String field) {
        List<String> ids = Objects.requireNonNull(vectorIds, field).stream()
                .map(id -> required(id, field)).distinct().sorted().toList();
        if (ids.isEmpty() || ids.size() > 100 || ids.size() != vectorIds.size()) {
            throw new IllegalArgumentException(field + " must contain 1-100 unique IDs");
        }
        return ids;
    }
}
