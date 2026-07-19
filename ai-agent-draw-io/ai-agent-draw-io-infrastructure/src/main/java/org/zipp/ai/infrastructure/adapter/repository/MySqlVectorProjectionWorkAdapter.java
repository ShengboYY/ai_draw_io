package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.VectorProjectionWorkPort;
import org.zipp.ai.domain.retrieval.projection.*;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** MySQL unit-of-work adapter for fenced, generation-scoped vector projection transitions. */
@Repository
public class MySqlVectorProjectionWorkAdapter implements VectorProjectionWorkPort {
    private final IVectorProjectionMapper mapper;
    private final IDocumentProcessingMapper documentMapper;
    private final IProcessingJobMapper jobMapper;
    private final MySqlVectorProjectionPersistence persistence;

    public MySqlVectorProjectionWorkAdapter(IVectorProjectionMapper mapper,
                                            IDocumentProcessingMapper documentMapper,
                                            IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.persistence = new MySqlVectorProjectionPersistence(mapper, jobMapper);
    }

    @Override
    public Optional<RevisionProjectionContext> findCoordinatorWork(String revisionId, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        return Optional.ofNullable(mapper.selectCoordinatorWork(required(revisionId, "revisionId"),
                current.jobId(), current.workerId(), current.fenceToken())).map(this::context);
    }

    @Override
    @Transactional
    public boolean commitCoordinator(RevisionProjectionContext work, VectorProjectionPlan result,
                                     List<ProcessingJob> nextJobs, WorkerFence fence) {
        RevisionProjectionContext source = Objects.requireNonNull(work, "work");
        VectorProjectionPlan plan = Objects.requireNonNull(result, "result");
        List<ProcessingJob> successors = List.copyOf(nextJobs);
        if (!source.revisionId().equals(plan.revisionId()) || !source.versionId().equals(plan.versionId())) {
            throw new IllegalArgumentException("vector plan does not belong to its revision");
        }
        validateCoordinatorSuccessors(source, plan, successors);
        if (!hasFence(source, ProcessingJobStage.BUILD_LEXICAL_PROJECTION, fence)) return false;
        persistence.persistPlan(source.revisionId(), plan, VectorProjectionRole.PRIMARY);
        if (documentMapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                ProcessingStage.INDEXING.name(), 95) != 1) {
            throw new IllegalStateException("revision generation became stale during vector coordination");
        }
        successors.forEach(job -> jobMapper.insert(toPo(job)));
        return true;
    }

    @Override
    public Optional<CompatibilityProjectionWork> findCompatibilityCoordinatorWork(
            String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        VectorProjectionWorkPO row = mapper.selectCompatibilityCoordinatorWork(
                required(revisionId, "revisionId"), required(workKey, "workKey"),
                current.jobId(), current.workerId(), current.fenceToken());
        return Optional.ofNullable(row).map(po -> new CompatibilityProjectionWork(context(po), profile(po)));
    }

    @Override
    @Transactional
    public boolean commitCompatibilityCoordinator(CompatibilityProjectionWork work,
                                                  VectorProjectionPlan result,
                                                  List<ProcessingJob> nextJobs, WorkerFence fence) {
        CompatibilityProjectionWork source = Objects.requireNonNull(work, "work");
        VectorProjectionPlan plan = Objects.requireNonNull(result, "result");
        List<ProcessingJob> successors = List.copyOf(nextJobs);
        if (!source.context().revisionId().equals(plan.revisionId())
                || !source.context().versionId().equals(plan.versionId())
                || !source.profile().equals(plan.profile())) {
            throw new IllegalArgumentException("compatibility plan does not belong to its target");
        }
        validateCoordinatorSuccessors(source.context(), plan, successors);
        if (!hasGenerationFence(source.context(), plan.generationId(),
                ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION, fence)) return false;
        persistence.persistPlan(source.context().revisionId(), plan, VectorProjectionRole.COMPATIBILITY);
        if (mapper.updateGenerationTargetState(source.context().revisionId(), plan.generationId(),
                "PENDING", "PLANNED") != 1) {
            throw new IllegalStateException("compatibility target was not pending");
        }
        successors.forEach(job -> jobMapper.insert(toPo(job)));
        return true;
    }

    @Override
    public Optional<VectorEmbeddingWork> findEmbeddingWork(String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        VectorProjectionWorkPO po = mapper.selectEmbeddingWork(required(revisionId, "revisionId"),
                required(workKey, "workKey"), current.jobId(), current.workerId(), current.fenceToken());
        return Optional.ofNullable(po).map(row -> new VectorEmbeddingWork(context(row), profile(row),
                role(row), row.getBatchNo(), row.getWorkKey(), row.getBatchInputFingerprint()));
    }

    @Override
    @Transactional
    public boolean commitEmbedding(VectorEmbeddingWork work, VectorBatchArtifactResult result,
                                   ProcessingJob nextJob, WorkerFence fence) {
        VectorEmbeddingWork source = Objects.requireNonNull(work, "work");
        VectorBatchArtifactResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        validateEmbeddingCommit(source, output, successor);
        if (!hasProjectionFence(source.context(), source.profile().generationId(),
                source.projectionRole(), ProcessingJobStage.EMBED_CHUNK_BATCHES, fence)) return false;
        StoredArtifact artifact = output.artifact();
        if (mapper.pinVectorBatch(source.context().revisionId(), source.profile().generationId(),
                source.batchNo(), artifact.objectKey(), artifact.objectVersionId(), artifact.contentSha256(),
                artifact.byteSize(), artifact.contentType()) != 1) {
            throw new IllegalStateException("vector batch artifact was not pending");
        }
        int changed = mapper.updateProjectionBatchState(source.context().revisionId(),
                source.profile().generationId(), source.batchNo(), VectorProjectionState.PENDING.name(),
                VectorProjectionState.EMBEDDED.name(), null);
        if (changed != output.payload().embeddings().size()) {
            throw new IllegalStateException("embedded projection count does not match the batch");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    @Override
    public Optional<VectorUpsertWork> findUpsertWork(String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        VectorProjectionWorkPO header = mapper.selectUpsertWork(required(revisionId, "revisionId"),
                required(workKey, "workKey"), current.jobId(), current.workerId(), current.fenceToken());
        if (header == null) return Optional.empty();
        List<VectorProjectionMetadata> projections = mapper.selectBatchProjections(header.getRevisionId(),
                header.getIndexGenerationId(), header.getBatchNo()).stream().map(this::metadata).toList();
        StoredArtifact vectorArtifact = new StoredArtifact(header.getVectorObjectKey(),
                header.getVectorObjectVersionId(), header.getVectorContentSha256(),
                header.getVectorByteSize(), header.getVectorContentType());
        return Optional.of(new VectorUpsertWork(context(header), profile(header), role(header), header.getBatchNo(),
                header.getWorkKey(), header.getBatchInputFingerprint(), vectorArtifact, projections));
    }

    @Override
    @Transactional
    public boolean commitUpsert(VectorUpsertWork work, VectorBatchPayload payload,
                                ProcessingJob verificationJob, WorkerFence fence) {
        VectorUpsertWork source = Objects.requireNonNull(work, "work");
        VectorBatchPayload vectors = Objects.requireNonNull(payload, "payload");
        ProcessingJob verifier = Objects.requireNonNull(verificationJob, "verificationJob");
        validateUpsertCommit(source, vectors, verifier);
        // Serialize the final-batch decision so concurrent upserts cannot both miss the verifier enqueue.
        if (!source.context().revisionId().equals(mapper.lockRevisionGate(source.context().revisionId()))) {
            return false;
        }
        if (!hasProjectionFence(source.context(), source.profile().generationId(),
                source.projectionRole(), ProcessingJobStage.UPSERT_VECTOR_BATCHES, fence)) return false;
        int changed = mapper.updateProjectionBatchState(source.context().revisionId(),
                source.profile().generationId(), source.batchNo(), VectorProjectionState.EMBEDDED.name(),
                VectorProjectionState.INDEXED.name(), Instant.now());
        if (changed != source.projections().size()
                || mapper.updateBatchState(source.context().revisionId(), source.profile().generationId(),
                        source.batchNo(), VectorProjectionState.EMBEDDED.name(),
                        VectorProjectionState.INDEXED.name()) != 1) {
            throw new IllegalStateException("upserted projection batch was not in the embedded state");
        }
        if (mapper.countIncompleteBatches(source.context().revisionId(), source.profile().generationId()) == 0) {
            jobMapper.insert(toPo(verifier));
        }
        return true;
    }

    @Override
    public Optional<VectorManifestWork> findManifestWork(String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<VectorProjectionWorkPO> rows = mapper.selectManifestWork(required(revisionId, "revisionId"),
                required(workKey, "workKey"), current.jobId(), current.workerId(), current.fenceToken());
        if (rows.isEmpty()) return Optional.empty();
        VectorProjectionWorkPO first = rows.get(0);
        List<VectorProjectionManifestEntry> entries = rows.stream().filter(row -> row.getChunkId() != null)
                .map(row -> new VectorProjectionManifestEntry(row.getChunkId(), row.getVectorId(),
                        row.getProjectionFingerprint())).toList();
        // A manifest is authoritative only when every dense-eligible chunk has an indexed projection.
        if (entries.size() != first.getEligibleProjectionCount()) {
            throw new IllegalStateException("indexed vector projection count is incomplete");
        }
        return Optional.of(new VectorManifestWork(context(first), profile(first),
                first.getProjectionRole() == null ? VectorProjectionRole.PRIMARY
                        : VectorProjectionRole.valueOf(first.getProjectionRole()), entries));
    }

    @Override
    @Transactional
    public boolean commitManifest(VectorManifestWork work, VectorProjectionManifestResult result,
                                  ProcessingJob nextJob, WorkerFence fence) {
        VectorManifestWork source = Objects.requireNonNull(work, "work");
        if (source.projectionRole() != VectorProjectionRole.PRIMARY) {
            throw new IllegalArgumentException("primary manifest commit requires a primary projection");
        }
        VectorProjectionManifestResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        validateManifestCommit(source, output, successor);
        if (!hasFence(source.context(), ProcessingJobStage.VERIFY_PROJECTION_MANIFEST, fence)) return false;
        persistManifest(source, output);
        if (mapper.updateRevisionProjectionState(source.context().revisionId(),
                source.profile().generationId(), RevisionVectorProjectionState.BUILDING.name(),
                RevisionVectorProjectionState.READY.name()) != 1) {
            throw new IllegalStateException("revision vector projection was not building");
        }
        if (documentMapper.advanceRevision(source.context().revisionId(),
                source.context().revisionFenceGeneration(), ProcessingStage.PUBLISHING.name(), 98) != 1) {
            throw new IllegalStateException("revision generation became stale during projection manifest commit");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    @Override
    @Transactional
    public boolean commitCompatibilityManifest(VectorManifestWork work,
                                               VectorProjectionManifestResult result,
                                               WorkerFence fence) {
        VectorManifestWork source = Objects.requireNonNull(work, "work");
        VectorProjectionManifestResult output = Objects.requireNonNull(result, "result");
        if (source.projectionRole() != VectorProjectionRole.COMPATIBILITY
                || !source.context().revisionId().equals(output.manifest().revisionId())
                || !source.context().versionId().equals(output.manifest().versionId())
                || !source.profile().generationId().equals(output.manifest().generationId())) {
            throw new IllegalArgumentException("compatibility manifest identity is invalid");
        }
        if (!hasGenerationFence(source.context(), source.profile().generationId(),
                ProcessingJobStage.VERIFY_PROJECTION_MANIFEST, fence)) return false;
        persistManifest(source, output);
        if (mapper.updateRevisionProjectionState(source.context().revisionId(),
                source.profile().generationId(), RevisionVectorProjectionState.BUILDING.name(),
                RevisionVectorProjectionState.READY.name()) != 1
                || mapper.updateGenerationTargetState(source.context().revisionId(),
                        source.profile().generationId(), "PLANNED", "READY") != 1) {
            throw new IllegalStateException("compatibility projection boundary became stale");
        }
        return true;
    }

    @Override
    public Optional<RevisionPublicationWork> findPublicationWork(
            String revisionId, String workKey, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<VectorProjectionWorkPO> rows = mapper.selectPublicationWork(
                required(revisionId, "revisionId"), required(workKey, "workKey"),
                current.jobId(), current.workerId(), current.fenceToken());
        return rows.isEmpty() ? Optional.empty() : Optional.of(publication(rows));
    }

    @Override
    @Transactional
    public boolean commitPublication(RevisionPublicationWork work, WorkerFence fence) {
        RevisionPublicationWork source = Objects.requireNonNull(work, "work");
        WorkerFence currentFence = Objects.requireNonNull(fence, "fence");
        // Publication shares the material-first lock order with leases, trash, and permanent deletion.
        if (!"ACTIVE".equals(mapper.lockMaterialLifecycleState(source.context().materialId()))) {
            return false;
        }
        if (!source.context().revisionId().equals(mapper.lockRevisionGate(source.context().revisionId()))) {
            return false;
        }
        String generationState = mapper.lockGenerationState(source.profile().generationId());
        if (generationState == null) return false;
        String activeGenerationId = mapper.selectActiveGenerationForUpdate();
        List<VectorProjectionWorkPO> currentRows = mapper.selectPublicationWork(
                source.context().revisionId(),
                RevisionPublicationWork.publicationWorkKey(source.profile().generationId()),
                currentFence.jobId(), currentFence.workerId(), currentFence.fenceToken());
        if (currentRows.isEmpty() || !samePublication(source, publication(currentRows))) return false;
        if (!hasFence(source.context(), ProcessingJobStage.PUBLISH_REVISION, currentFence)) return false;

        IndexGenerationState state = IndexGenerationState.valueOf(generationState);
        if (state == IndexGenerationState.BUILDING) {
            if (activeGenerationId != null) {
                throw new IllegalStateException("another active generation requires compatibility projection");
            }
            if (mapper.activateInitialGeneration(source.profile().generationId(), Instant.now()) != 1) {
                throw new IllegalStateException("initial index generation activation lost its fence");
            }
        } else if (state != IndexGenerationState.ACTIVE
                || !source.profile().generationId().equals(activeGenerationId)) {
            throw new IllegalStateException("revision publication requires its active generation");
        }
        if (mapper.publishRevision(source.context().revisionId(),
                source.context().revisionFenceGeneration(), source.publicationState().name(), Instant.now()) != 1
                || mapper.activateVersionRevision(source.context().versionId(),
                        source.context().revisionId()) != 1) {
            throw new IllegalStateException("revision publication boundary became stale");
        }
        // INSERT IGNORE makes retries and later revisions of the same content non-billable.
        mapper.recordInitialProcessingUsage(source.context().revisionId());
        return true;
    }

    private void validateCoordinatorSuccessors(RevisionProjectionContext source, VectorProjectionPlan plan,
                                               List<ProcessingJob> successors) {
        if (plan.batches().isEmpty()) {
            String fingerprint = VectorManifestWork.verificationInputFingerprint(
                    source.revisionId(), plan.generationId());
            if (successors.size() != 1 || successors.get(0).stage() != ProcessingJobStage.VERIFY_PROJECTION_MANIFEST
                    || !VectorManifestWork.verificationWorkKey(plan.generationId())
                            .equals(successors.get(0).workKey())
                    || !fingerprint.equals(successors.get(0).inputFingerprint())) {
                throw new IllegalArgumentException("lexical-only revision requires its manifest verifier");
            }
            return;
        }
        Map<String, VectorBatchPlan> byWorkKey = plan.batches().stream()
                .collect(Collectors.toUnmodifiableMap(VectorBatchPlan::workKey, Function.identity()));
        if (successors.size() != plan.batches().size() || successors.stream().anyMatch(job -> {
            VectorBatchPlan batch = byWorkKey.get(job.workKey());
            return job.stage() != ProcessingJobStage.EMBED_CHUNK_BATCHES
                    || batch == null || !batch.inputFingerprint().equals(job.inputFingerprint());
        })) {
            throw new IllegalArgumentException("vector coordinator successors do not match their batch plan");
        }
    }

    private void validateEmbeddingCommit(VectorEmbeddingWork source, VectorBatchArtifactResult output,
                                         ProcessingJob successor) {
        VectorBatchPayload payload = output.payload();
        if (!source.context().revisionId().equals(payload.revisionId())
                || !source.profile().generationId().equals(payload.generationId())
                || source.batchNo() != payload.batchNo()
                || !source.batchInputFingerprint().equals(payload.batchInputFingerprint())
                || successor.stage() != ProcessingJobStage.UPSERT_VECTOR_BATCHES
                || !source.workKey().equals(successor.workKey())
                || !VectorUpsertWork.upsertInputFingerprint(source.workKey(), source.batchInputFingerprint(),
                        output.artifact().contentSha256()).equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("embedding commit identity is invalid");
        }
    }

    private void validateUpsertCommit(VectorUpsertWork source, VectorBatchPayload payload,
                                      ProcessingJob verifier) {
        Set<String> payloadIds = payload.embeddings().stream().map(VectorEmbeddingValue::chunkId)
                .collect(Collectors.toSet());
        Set<String> projectionIds = source.projections().stream().map(VectorProjectionMetadata::chunkId)
                .collect(Collectors.toSet());
        String fingerprint = VectorManifestWork.verificationInputFingerprint(
                source.context().revisionId(), source.profile().generationId());
        if (!payloadIds.equals(projectionIds) || verifier.stage() != ProcessingJobStage.VERIFY_PROJECTION_MANIFEST
                || !VectorManifestWork.verificationWorkKey(source.profile().generationId())
                        .equals(verifier.workKey())
                || !fingerprint.equals(verifier.inputFingerprint())) {
            throw new IllegalArgumentException("upsert commit identity is invalid");
        }
    }

    private void validateManifestCommit(VectorManifestWork source, VectorProjectionManifestResult output,
                                        ProcessingJob successor) {
        var manifest = output.manifest();
        String expected = RevisionPublicationWork.publicationInputFingerprint(
                output.artifact().contentSha256(), manifest.manifestHash());
        if (!source.context().revisionId().equals(manifest.revisionId())
                || !source.context().versionId().equals(manifest.versionId())
                || !source.profile().generationId().equals(manifest.generationId())
                || successor.stage() != ProcessingJobStage.PUBLISH_REVISION
                || !RevisionPublicationWork.publicationWorkKey(source.profile().generationId())
                        .equals(successor.workKey())
                || !expected.equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("projection manifest commit identity is invalid");
        }
    }

    private boolean hasFence(RevisionProjectionContext context, ProcessingJobStage stage, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        return documentMapper.countCurrentFence(context.revisionId(), context.revisionFenceGeneration(),
                context.materialLifecycleGeneration(), stage.name(), current.jobId(), current.workerId(),
                current.fenceToken()) == 1;
    }

    private boolean hasProjectionFence(RevisionProjectionContext context, String generationId,
                                       VectorProjectionRole role, ProcessingJobStage stage,
                                       WorkerFence fence) {
        return role == VectorProjectionRole.PRIMARY
                ? hasFence(context, stage, fence)
                : hasGenerationFence(context, generationId, stage, fence);
    }

    private boolean hasGenerationFence(RevisionProjectionContext context, String generationId,
                                       ProcessingJobStage stage, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        return mapper.countGenerationRoutedFence(context.revisionId(), generationId, stage.name(),
                current.jobId(), current.workerId(), current.fenceToken()) == 1;
    }

    private RevisionProjectionContext context(VectorProjectionWorkPO po) {
        return persistence.context(po);
    }

    private RevisionPublicationWork publication(List<VectorProjectionWorkPO> rows) {
        VectorProjectionWorkPO first = rows.get(0);
        StoredArtifact projectionManifest = new StoredArtifact(first.getProjectionManifestKey(),
                first.getProjectionManifestVersionId(), first.getProjectionManifestSha256(),
                first.getProjectionManifestSize(), first.getProjectionManifestContentType());
        StoredArtifact structure = new StoredArtifact(first.getStructureKey(), first.getStructureVersionId(),
                first.getStructureSha256(), first.getStructureSize(), first.getStructureContentType());
        StoredArtifact evidence = new StoredArtifact(first.getEvidenceManifestKey(),
                first.getEvidenceManifestVersionId(), first.getEvidenceManifestSha256(),
                first.getEvidenceManifestSize(), first.getEvidenceManifestContentType());
        StoredArtifact gaps = first.getGapManifestKey() == null ? null : new StoredArtifact(
                first.getGapManifestKey(), first.getGapManifestVersionId(), first.getGapManifestSha256(),
                Objects.requireNonNull(first.getGapManifestSize(), "gapManifestSize"),
                first.getGapManifestContentType());
        List<VectorProjectionManifestEntry> indexed = rows.stream()
                .filter(row -> row.getVectorId() != null)
                .map(row -> new VectorProjectionManifestEntry(row.getChunkId(), row.getVectorId(),
                        row.getProjectionFingerprint())).toList();
        return new RevisionPublicationWork(context(first), profile(first), structure, evidence, gaps,
                projectionManifest, first.getProjectionManifestHash(), first.getRetrievalChunkCount(),
                first.getLexicalProjectionCount(), first.getExactTermCount(),
                first.getChunkEvidenceMappingCount(), first.getEvidenceUnitCount(),
                first.getExpectedProjectionCount(),
                first.getIndexedProjectionCount(), IndexGenerationState.valueOf(first.getGenerationState()),
                first.getActiveGenerationId(), indexed);
    }

    private boolean samePublication(RevisionPublicationWork expected, RevisionPublicationWork current) {
        return expected.context().revisionId().equals(current.context().revisionId())
                && expected.context().versionId().equals(current.context().versionId())
                && expected.context().revisionFenceGeneration() == current.context().revisionFenceGeneration()
                && expected.context().materialLifecycleGeneration()
                        == current.context().materialLifecycleGeneration()
                && expected.profile().equals(current.profile())
                && expected.structureArtifact().equals(current.structureArtifact())
                && expected.evidenceManifestArtifact().equals(current.evidenceManifestArtifact())
                && Objects.equals(expected.gapManifestArtifact(), current.gapManifestArtifact())
                && expected.projectionManifestArtifact().equals(current.projectionManifestArtifact())
                && expected.projectionManifestHash().equals(current.projectionManifestHash())
                && expected.retrievalChunkCount() == current.retrievalChunkCount()
                && expected.lexicalProjectionCount() == current.lexicalProjectionCount()
                && expected.exactTermCount() == current.exactTermCount()
                && expected.chunkEvidenceMappingCount() == current.chunkEvidenceMappingCount()
                && expected.evidenceUnitCount() == current.evidenceUnitCount()
                && expected.expectedProjectionCount() == current.expectedProjectionCount()
                && expected.indexedProjectionCount() == current.indexedProjectionCount()
                && new HashSet<>(expected.indexedProjections())
                        .equals(new HashSet<>(current.indexedProjections()));
    }

    private VectorGenerationProfile profile(VectorProjectionWorkPO po) {
        return persistence.profile(po);
    }

    private VectorProjectionRole role(VectorProjectionWorkPO po) {
        return po.getProjectionRole() == null ? VectorProjectionRole.PRIMARY
                : VectorProjectionRole.valueOf(po.getProjectionRole());
    }

    private VectorProjectionMetadata metadata(VectorProjectionWorkPO po) {
        return new VectorProjectionMetadata(po.getChunkId(), po.getVectorId(), po.getProjectionFingerprint(),
                RetrievalChunkType.valueOf(po.getChunkType()),
                org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.valueOf(po.getModality()),
                po.getPageNo(), po.getLanguage());
    }

    private void persistManifest(VectorManifestWork source, VectorProjectionManifestResult result) {
        VectorProjectionManifestPO po = new VectorProjectionManifestPO();
        po.setRevisionId(source.context().revisionId());
        po.setIndexGenerationId(source.profile().generationId());
        po.setObjectKey(result.artifact().objectKey());
        po.setObjectVersionId(result.artifact().objectVersionId());
        po.setContentSha256(result.artifact().contentSha256());
        po.setByteSize(result.artifact().byteSize());
        po.setContentType(result.artifact().contentType());
        po.setManifestHash(result.manifest().manifestHash());
        po.setProjectionCount(result.manifest().entries().size());
        po.setState("READY");
        mapper.insertManifest(po);
        VectorProjectionManifestPO persisted = mapper.selectManifest(po.getRevisionId(), po.getIndexGenerationId());
        if (persisted == null || !po.getObjectKey().equals(persisted.getObjectKey())
                || !po.getObjectVersionId().equals(persisted.getObjectVersionId())
                || !po.getContentSha256().equals(persisted.getContentSha256())
                || po.getByteSize() != persisted.getByteSize()
                || !po.getContentType().equals(persisted.getContentType())
                || !po.getManifestHash().equals(persisted.getManifestHash())
                || po.getProjectionCount() != persisted.getProjectionCount()) {
            throw new IllegalStateException("projection manifest collided with different content");
        }
    }

    private ProcessingJobPO toPo(ProcessingJob job) {
        return persistence.processingJob(job);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
