package org.zipp.ai.infrastructure.adapter.repository;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.retrieval.model.valobj.RevisionProjectionContext;
import org.zipp.ai.domain.retrieval.model.valobj.RevisionVectorProjectionState;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjectionRole;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjectionState;
import org.zipp.ai.domain.retrieval.projection.VectorBatchPlan;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionPlan;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionTarget;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Shared MySQL mapping for immutable generation plans used by primary and compatibility workflows. */
final class MySqlVectorProjectionPersistence {
    private final IVectorProjectionMapper mapper;
    private final IProcessingJobMapper jobMapper;

    MySqlVectorProjectionPersistence(IVectorProjectionMapper mapper, IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    void persistPlan(String revisionId, VectorProjectionPlan plan, VectorProjectionRole role) {
        persistGeneration(plan.profile());
        persistRevisionProjection(revisionId, plan, role);
        Map<String, Integer> batchByChunk = new HashMap<>();
        for (VectorBatchPlan batch : plan.batches()) {
            persistBatch(revisionId, plan.generationId(), batch);
            batch.projections().forEach(projection -> batchByChunk.put(projection.chunkId(), batch.batchNo()));
        }
        for (VectorProjectionTarget target : plan.projections()) {
            persistProjection(plan.profile(), target, batchByChunk.get(target.chunkId()), role);
        }
    }

    void persistGeneration(VectorGenerationProfile profile) {
        RagIndexGenerationPO po = new RagIndexGenerationPO();
        po.setId(profile.generationId());
        po.setIndexName(profile.indexName());
        po.setNamespace(profile.namespace());
        po.setEmbeddingModel(profile.embeddingModel());
        po.setEmbeddingModelFingerprint(profile.embeddingModelFingerprint());
        po.setDimension(profile.dimension());
        po.setMetric(profile.metric());
        po.setVectorSchemaVersion(profile.vectorSchemaVersion());
        po.setState("BUILDING");
        mapper.insertGeneration(po);
        RagIndexGenerationPO persisted = mapper.selectGeneration(po.getId());
        if (persisted == null || !po.getIndexName().equals(persisted.getIndexName())
                || !po.getNamespace().equals(persisted.getNamespace())
                || !po.getEmbeddingModel().equals(persisted.getEmbeddingModel())
                || !po.getEmbeddingModelFingerprint().equals(persisted.getEmbeddingModelFingerprint())
                || po.getDimension() != persisted.getDimension() || !po.getMetric().equals(persisted.getMetric())
                || !po.getVectorSchemaVersion().equals(persisted.getVectorSchemaVersion())) {
            throw new IllegalStateException("index generation collided with different configuration");
        }
    }

    RevisionProjectionContext context(VectorProjectionWorkPO po) {
        StoredArtifact manifest = new StoredArtifact(po.getRetrievalManifestKey(),
                po.getRetrievalManifestVersionId(), po.getRetrievalManifestSha256(),
                po.getRetrievalManifestSize(), po.getRetrievalManifestContentType());
        return new RevisionProjectionContext(po.getRevisionId(), po.getVersionId(), po.getMaterialId(),
                OwnerType.valueOf(po.getOwnerType()), po.getOwnerKey(), po.getRevisionFenceGeneration(),
                po.getMaterialLifecycleGeneration(), po.getProcessingFingerprint(), manifest);
    }

    VectorGenerationProfile profile(VectorProjectionWorkPO po) {
        return new VectorGenerationProfile(po.getIndexName(), po.getNamespace(), po.getEmbeddingModel(),
                po.getEmbeddingModelFingerprint(), po.getDimension(), po.getMetric(),
                po.getVectorSchemaVersion(), po.getTokenizerFingerprint());
    }

    ProcessingJobPO processingJob(ProcessingJob job) {
        ProcessingJobPO po = new ProcessingJobPO();
        po.setId(job.id());
        po.setUploadSessionId(job.target().uploadSessionId());
        po.setRevisionId(job.target().revisionId());
        po.setStage(job.stage().name());
        po.setWorkKey(job.workKey());
        po.setInputFingerprint(job.inputFingerprint());
        po.setPriority(job.priority());
        po.setStatus(job.status().name());
        po.setAttempt(job.attempt());
        po.setNotBefore(job.notBefore());
        po.setFenceToken(job.fenceToken());
        return po;
    }

    void enqueue(ProcessingJob job) {
        jobMapper.insert(processingJob(job));
    }

    private void persistRevisionProjection(String revisionId, VectorProjectionPlan plan,
                                           VectorProjectionRole role) {
        RevisionVectorProjectionPO po = new RevisionVectorProjectionPO();
        po.setRevisionId(revisionId);
        po.setIndexGenerationId(plan.generationId());
        po.setTokenizerFingerprint(plan.profile().tokenizerFingerprint());
        po.setPlanFingerprint(plan.planFingerprint());
        po.setProjectionRole(role.name());
        po.setExpectedProjectionCount(plan.projections().size());
        po.setState(RevisionVectorProjectionState.BUILDING.name());
        mapper.insertRevisionProjection(po);
        RevisionVectorProjectionPO persisted = mapper.selectRevisionProjection(revisionId, plan.generationId());
        if (persisted == null || !po.getTokenizerFingerprint().equals(persisted.getTokenizerFingerprint())
                || !po.getPlanFingerprint().equals(persisted.getPlanFingerprint())
                || !po.getProjectionRole().equals(persisted.getProjectionRole())
                || po.getExpectedProjectionCount() != persisted.getExpectedProjectionCount()) {
            throw new IllegalStateException("revision vector projection collided with a different plan");
        }
    }

    private void persistBatch(String revisionId, String generationId, VectorBatchPlan batch) {
        VectorBatchPO po = new VectorBatchPO();
        po.setRevisionId(revisionId);
        po.setIndexGenerationId(generationId);
        po.setBatchNo(batch.batchNo());
        po.setWorkKey(batch.workKey());
        po.setInputFingerprint(batch.inputFingerprint());
        po.setState(VectorProjectionState.PENDING.name());
        mapper.insertBatch(po);
        VectorBatchPO persisted = mapper.selectBatch(revisionId, generationId, batch.batchNo());
        if (persisted == null || !po.getWorkKey().equals(persisted.getWorkKey())
                || !po.getInputFingerprint().equals(persisted.getInputFingerprint())) {
            throw new IllegalStateException("vector batch collided with different identity");
        }
    }

    private void persistProjection(VectorGenerationProfile profile, VectorProjectionTarget target,
                                   int batchNo, VectorProjectionRole role) {
        VectorProjectionPO po = new VectorProjectionPO();
        po.setRetrievalChunkId(target.chunkId());
        po.setIndexGenerationId(profile.generationId());
        po.setBatchNo(batchNo);
        po.setIndexName(profile.indexName());
        po.setNamespace(profile.namespace());
        po.setVectorId(target.vectorId());
        po.setEmbeddingModel(profile.embeddingModel());
        po.setEmbeddingFingerprint(target.projectionFingerprint());
        po.setDimension(profile.dimension());
        po.setProjectionRole(role.name());
        po.setState(VectorProjectionState.PENDING.name());
        mapper.insertProjection(po);
        VectorProjectionPO persisted = mapper.selectProjection(target.chunkId(), profile.generationId());
        if (persisted == null || persisted.getBatchNo() != batchNo
                || !po.getIndexName().equals(persisted.getIndexName())
                || !po.getNamespace().equals(persisted.getNamespace())
                || !po.getVectorId().equals(persisted.getVectorId())
                || !po.getEmbeddingModel().equals(persisted.getEmbeddingModel())
                || !po.getEmbeddingFingerprint().equals(persisted.getEmbeddingFingerprint())
                || po.getDimension() != persisted.getDimension()
                || !po.getProjectionRole().equals(persisted.getProjectionRole())) {
            throw new IllegalStateException("vector projection collided with different identity");
        }
    }
}
