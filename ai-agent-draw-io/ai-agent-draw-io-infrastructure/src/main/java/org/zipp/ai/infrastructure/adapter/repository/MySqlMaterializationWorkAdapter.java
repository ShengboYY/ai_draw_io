package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationIds;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationResult;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.OwnedContentBlob;
import org.zipp.ai.domain.ingestion.model.valobj.OwnedMaterialVersion;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.MaterializationWorkPort;
import org.zipp.ai.domain.ingestion.service.ContentMaterializationPolicy;
import org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy;
import org.zipp.ai.domain.ingestion.service.ProcessingRevisionFingerprintPolicy;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.aggregate.MaterialVersion;
import org.zipp.ai.domain.material.model.valobj.MaterialKind;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeLink;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.infrastructure.dao.material.IMaterializationMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.ContentBlobPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialVersionMatchPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialVersionPO;
import org.zipp.ai.infrastructure.dao.material.po.OriginalPromotionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingRevisionPO;
import org.zipp.ai.infrastructure.dao.material.po.UploadSessionPO;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Repository
public class MySqlMaterializationWorkAdapter implements MaterializationWorkPort {

    private final IMaterializationMapper mapper;
    private final IProcessingJobMapper jobMapper;
    private final ContentMaterializationPolicy policy = new ContentMaterializationPolicy();

    public MySqlMaterializationWorkAdapter(IMaterializationMapper mapper, IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    @Override
    @Transactional
    public MaterializationResult resolveAndMaterialize(String uploadId, MaterializationIds ids,
                                                       ProcessingRevisionProfile processingProfile,
                                                       ProcessingJob promotionJob,
                                                       ProcessingJob extractionJob,
                                                       WorkerFence fence, Instant now) {
        WorkerFence workerFence = Objects.requireNonNull(fence, "fence");
        ProcessingRevisionProfile revisionProfile = Objects.requireNonNull(processingProfile,
                "processingProfile");
        UploadSessionPO upload = mapper.selectUploadForMaterialization(
                requireText(uploadId, "uploadId"), workerFence.jobId(), workerFence.workerId(),
                workerFence.fenceToken());
        if (upload == null) {
            throw new IllegalStateException("validated upload is unavailable to the current fence");
        }
        if ("SUCCEEDED".equals(upload.getState())) {
            return result(MaterializationResult.Outcome.COMPLETED, upload);
        }
        if (upload.getContentBlobId() != null) {
            return result(MaterializationResult.Outcome.PROMOTION_QUEUED, upload);
        }
        validateSecurityFacts(upload);

        ContentBlobPO blob = resolveBlob(upload, ids.contentBlobId());
        MaterialVersionMatchPO reusable = mapper.selectReusableVersionForUpdate(
                upload.getOwnerType(), upload.getOwnerKey(), blob.getId());
        MaterialVersionMatchPO targetVersion = upload.getNewVersionOfMaterialId() == null ? null
                : mapper.selectTargetVersionForUpdate(upload.getNewVersionOfMaterialId(), upload.getOwnerType(),
                upload.getOwnerKey(), blob.getId());
        var decision = policy.decide(upload.getNewVersionOfMaterialId(), toDomain(blob),
                toDomain(reusable), toDomain(targetVersion));

        MaterialScopeLinkPO scopeLink = scopeLink(ids.scopeLinkId(), upload,
                decision.action() == ContentMaterializationPolicy.Action.CREATE_MATERIAL
                        ? ids.materialId() : decision.materialId());
        MaterialVersionMatchPO resolved;
        boolean createdRevision;
        switch (decision.action()) {
            case REUSE_VERSION -> {
                resolved = reusableMatch(decision);
                createdRevision = false;
            }
            case CREATE_VERSION -> {
                resolved = createVersion(upload, ids, blob, decision.materialId(),
                        revisionProfile);
                createdRevision = true;
            }
            case CREATE_MATERIAL -> {
                createMaterial(upload, ids.materialId(), now);
                resolved = createVersion(upload, ids, blob, ids.materialId(),
                        revisionProfile);
                createdRevision = true;
            }
            default -> throw new IllegalStateException("unsupported materialization action");
        }
        // Scope attachment is idempotent and remains inside the same owner-locked transaction.
        scopeLink.setMaterialId(resolved.getMaterialId());
        mapper.insertScopeLink(scopeLink);
        MaterialPO activeMaterial = mapper.selectActiveMaterialForUpdate(
                resolved.getMaterialId(), upload.getOwnerType(), upload.getOwnerKey());
        if (activeMaterial == null) {
            throw new IllegalStateException("material is no longer active for this owner");
        }
        if (RetentionClass.RETAINED.name().equals(upload.getTargetRetentionClass())
                && RetentionClass.TEMPORARY.name().equals(activeMaterial.getRetentionClass())) {
            long expectedGeneration = activeMaterial.getLifecycleGeneration();
            Material temporary = Material.rehydrateTemporaryActive(activeMaterial.getId(),
                    OwnerType.valueOf(activeMaterial.getOwnerType()), activeMaterial.getOwnerKey(),
                    MaterialKind.valueOf(activeMaterial.getKind()), activeMaterial.getDisplayName(),
                    activeMaterial.getOriginConversationId(), expectedGeneration,
                    activeMaterial.getLastMeaningfulActivityAt(), activeMaterial.getExpiresAt());
            temporary.retain(MaterialScopeLink.of(MaterialScopeType.valueOf(upload.getTargetScopeType()),
                    upload.getTargetScopeKey(), upload.getOwnerKey()), now);
            if (mapper.persistRetainedMaterial(activeMaterial.getId(), activeMaterial.getOwnerType(),
                    activeMaterial.getOwnerKey(), expectedGeneration, temporary.lifecycleGeneration()) != 1) {
                throw new IllegalStateException("material retention generation became stale");
            }
            activeMaterial.setLifecycleGeneration(temporary.lifecycleGeneration());
        }

        boolean available = "AVAILABLE".equals(blob.getStatus());
        if (available) {
            if (mapper.completeWithoutPromotion(upload.getId(), upload.getGeneration(), resolved.getMaterialId(),
                    resolved.getVersionId(), blob.getId(), resolved.getRevisionId(),
                    activeMaterial.getLifecycleGeneration(), workerFence.jobId(), workerFence.workerId(),
                    workerFence.fenceToken()) != 1) {
                throw new IllegalStateException("materialization fence became stale before completion");
            }
            if (createdRevision) {
                jobMapper.insert(extractionPo(extractionJob, blob.getContentSha256(), revisionProfile.fingerprint()));
            }
            return new MaterializationResult(createdRevision
                    ? MaterializationResult.Outcome.EXTRACTION_QUEUED
                    : MaterializationResult.Outcome.REUSED_VERSION,
                    resolved.getMaterialId(), resolved.getVersionId(), resolved.getRevisionId());
        }
        if (mapper.correlateForPromotion(upload.getId(), upload.getGeneration(), resolved.getMaterialId(),
                resolved.getVersionId(), blob.getId(), resolved.getRevisionId(),
                activeMaterial.getLifecycleGeneration(), workerFence.jobId(), workerFence.workerId(),
                workerFence.fenceToken()) != 1) {
            throw new IllegalStateException("materialization fence became stale before promotion enqueue");
        }
        jobMapper.insert(toPo(promotionJob));
        return new MaterializationResult(MaterializationResult.Outcome.PROMOTION_QUEUED,
                resolved.getMaterialId(), resolved.getVersionId(), resolved.getRevisionId());
    }

    @Override
    public Optional<OriginalPromotionWork> findPromotionWork(String uploadId, WorkerFence fence) {
        WorkerFence workerFence = Objects.requireNonNull(fence, "fence");
        return Optional.ofNullable(mapper.selectPromotionWork(requireText(uploadId, "uploadId"),
                        workerFence.jobId(), workerFence.workerId(), workerFence.fenceToken()))
                .map(MySqlMaterializationWorkAdapter::toDomain);
    }

    @Override
    @Transactional
    public boolean commitPromotion(OriginalPromotionWork work, PromotedOriginal promoted,
                                   ProcessingJob extractionJob, WorkerFence fence) {
        OriginalPromotionWork source = Objects.requireNonNull(work, "work");
        PromotedOriginal result = Objects.requireNonNull(promoted, "promoted");
        WorkerFence workerFence = Objects.requireNonNull(fence, "fence");
        // The first successful copy fixes the formal VersionId; shared uploads may only reuse that exact pin.
        mapper.pinBlobIfPromoting(source.uploadId(), source.contentBlobId(), result.objectKey(),
                result.objectVersionId(), result.eTag(), result.checksumSha256(), workerFence.jobId(),
                workerFence.workerId(), workerFence.fenceToken());
        if (mapper.countFixedBlobForPromotion(source.uploadId(), source.contentBlobId(), result.objectKey(),
                result.objectVersionId(), result.checksumSha256(), workerFence.jobId(), workerFence.workerId(),
                workerFence.fenceToken()) != 1) {
            throw new IllegalStateException("formal content pin did not match the current promotion fence");
        }
        mapper.markVersionProcessing(source.uploadId(), source.versionId(), workerFence.jobId(), workerFence.workerId(),
                workerFence.fenceToken());
        if (mapper.countProcessableVersionForPromotion(source.uploadId(), source.versionId(), source.revisionId(),
                workerFence.jobId(), workerFence.workerId(), workerFence.fenceToken()) != 1) {
            throw new IllegalStateException("promoted version or revision was not processable");
        }
        if (mapper.completePromotedUpload(source.uploadId(), source.uploadGeneration(), workerFence.jobId(),
                workerFence.workerId(), workerFence.fenceToken()) != 1) {
            // Throw so Spring rolls back the blob/version mutations performed earlier in this transaction.
            throw new IllegalStateException("promotion fence became stale before upload completion");
        }
        ProcessingJob requestedExtraction = Objects.requireNonNull(extractionJob, "extractionJob");
        if (!requestedExtraction.inputFingerprint().equals(ProcessingStageFingerprintPolicy.extractionInput(
                source.contentSha256(), source.processingFingerprint()))) {
            throw new IllegalArgumentException("extraction job does not match the persisted revision profile");
        }
        jobMapper.insert(toPo(requestedExtraction));
        return true;
    }

    private ContentBlobPO resolveBlob(UploadSessionPO upload, String candidateId) {
        ContentBlobPO candidate = new ContentBlobPO();
        candidate.setId(candidateId);
        candidate.setOwnerType(upload.getOwnerType());
        candidate.setOwnerKey(upload.getOwnerKey());
        candidate.setContentSha256(upload.getActualSha256());
        candidate.setByteSize(upload.getActualSize());
        candidate.setDetectedMime(upload.getDetectedMime());
        candidate.setOriginalObjectKey("original/" + candidateId + "/" + upload.getActualSha256());
        candidate.setStatus("PROMOTING");
        mapper.insertContentBlob(candidate);
        ContentBlobPO resolved = mapper.selectContentBlobForUpdate(upload.getOwnerType(), upload.getOwnerKey(),
                upload.getActualSha256(), upload.getActualSize());
        if (resolved == null) {
            throw new IllegalStateException("content identity winner was not readable");
        }
        if ("DELETED".equals(resolved.getStatus())) {
            candidate.setId(resolved.getId());
            mapper.reviveDeletedBlob(candidate);
            resolved = mapper.selectContentBlobForUpdate(upload.getOwnerType(), upload.getOwnerKey(),
                    upload.getActualSha256(), upload.getActualSize());
        }
        return resolved;
    }

    private void createMaterial(UploadSessionPO upload, String materialId, Instant now) {
        OwnerType ownerType = OwnerType.valueOf(upload.getOwnerType());
        MaterialKind kind = "application/pdf".equals(upload.getDetectedMime())
                ? MaterialKind.PDF : MaterialKind.IMAGE;
        Material material;
        if (RetentionClass.TEMPORARY.name().equals(upload.getTargetRetentionClass())) {
            material = Material.createTemporary(materialId, ownerType, upload.getOwnerKey(), kind,
                    upload.getDisplayName(), upload.getTargetScopeKey(), now);
        } else {
            material = Material.createRetained(materialId, ownerType, upload.getOwnerKey(), kind,
                    upload.getDisplayName(), MaterialScopeLink.of(
                            MaterialScopeType.valueOf(upload.getTargetScopeType()), upload.getTargetScopeKey(),
                            upload.getOwnerKey()), now);
        }
        mapper.insertMaterial(toPo(material));
    }

    private MaterialVersionMatchPO createVersion(UploadSessionPO upload, MaterializationIds ids,
                                                  ContentBlobPO blob, String materialId,
                                                  ProcessingRevisionProfile processingProfile) {
        if (!materialId.equals(ids.materialId())) {
            MaterialPO target = mapper.selectActiveMaterialForUpdate(
                    materialId, upload.getOwnerType(), upload.getOwnerKey());
            if (target == null) {
                throw new IllegalStateException("target material is no longer active for this owner");
            }
        }
        int versionNo = materialId.equals(ids.materialId()) ? 1 : mapper.selectNextVersionNo(materialId);
        MaterialVersion version = MaterialVersion.create(ids.versionId(), materialId, upload.getOwnerKey(),
                versionNo, blob.getId(), upload.getActualSha256(), upload.getActualSize());
        ProcessingRevision revision = ProcessingRevision.start(ids.revisionId(), version.id(), 1,
                ProcessingRevisionFingerprintPolicy.processingFingerprint(
                        processingProfile.fingerprint(), java.util.Set.of()), java.util.Set.of());
        mapper.insertVersion(toPo(version, upload, "AVAILABLE".equals(blob.getStatus())
                ? "PROCESSING" : "PROMOTING"));
        mapper.insertRevision(toPo(revision, processingProfile));
        mapper.updateLatestVersion(materialId, version.id());
        MaterialVersionMatchPO match = new MaterialVersionMatchPO();
        match.setMaterialId(materialId);
        match.setVersionId(version.id());
        match.setRevisionId(revision.id());
        match.setContentBlobId(blob.getId());
        match.setMaterialLifecycleGeneration(0);
        return match;
    }

    private static void validateSecurityFacts(UploadSessionPO upload) {
        if (upload.getActualSize() == null || upload.getActualSize() < 1
                || upload.getActualSha256() == null || upload.getDetectedMime() == null) {
            throw new IllegalStateException("validated upload is missing authoritative content facts");
        }
    }

    private static MaterialVersionMatchPO reusableMatch(ContentMaterializationPolicy.Decision decision) {
        MaterialVersionMatchPO match = new MaterialVersionMatchPO();
        match.setMaterialId(decision.materialId());
        match.setVersionId(decision.versionId());
        match.setRevisionId(decision.revisionId());
        match.setContentBlobId(decision.contentBlob().id());
        return match;
    }

    private static MaterialScopeLinkPO scopeLink(String id, UploadSessionPO upload, String materialId) {
        MaterialScopeLinkPO link = new MaterialScopeLinkPO();
        link.setId(id);
        link.setMaterialId(materialId);
        link.setScopeType(upload.getTargetScopeType());
        link.setScopeKey(upload.getTargetScopeKey());
        link.setCreatedBy(upload.getOwnerKey());
        return link;
    }

    private static OwnedContentBlob toDomain(ContentBlobPO blob) {
        return blob == null ? null : new OwnedContentBlob(
                blob.getId(), blob.getStatus(), blob.getOriginalObjectKey());
    }

    private static OwnedMaterialVersion toDomain(MaterialVersionMatchPO version) {
        return version == null ? null : new OwnedMaterialVersion(version.getMaterialId(), version.getVersionId(),
                version.getRevisionId(), version.getContentBlobId());
    }

    private static OriginalPromotionWork toDomain(OriginalPromotionWorkPO work) {
        PromotedOriginal fixedOriginal = work.getFormalObjectVersionId() == null ? null
                : new PromotedOriginal(work.getDestinationKey(), work.getFormalObjectVersionId(),
                work.getFormalEtag(), work.getFormalChecksumSha256(), work.getByteSize());
        return new OriginalPromotionWork(work.getUploadId(), work.getUploadGeneration(),
                work.getQuarantineBucket(), work.getQuarantineKey(), work.getQuarantineVersionId(),
                work.getDestinationKey(), work.getContentBlobId(), work.getMaterialId(), work.getVersionId(),
                work.getRevisionId(), work.getDetectedMediaType(), work.getByteSize(), work.getContentSha256(),
                work.getProcessingFingerprint(),
                fixedOriginal);
    }

    private static MaterializationResult result(MaterializationResult.Outcome outcome, UploadSessionPO upload) {
        return new MaterializationResult(outcome, upload.getMaterialId(), upload.getVersionId(),
                upload.getProcessingRevisionId());
    }

    private static MaterialPO toPo(Material material) {
        MaterialPO po = new MaterialPO();
        po.setId(material.id());
        po.setOwnerType(material.ownerType().name());
        po.setOwnerKey(material.ownerKey());
        po.setKind(material.kind().name());
        po.setDisplayName(material.displayName());
        po.setRetentionClass(material.retentionClass().name());
        po.setOriginConversationId(material.originConversationId());
        po.setLifecycleState(material.lifecycleState().name());
        po.setLifecycleGeneration(material.lifecycleGeneration());
        po.setLastMeaningfulActivityAt(material.lastMeaningfulActivityAt());
        po.setExpiresAt(material.expiresAt());
        return po;
    }

    private static MaterialVersionPO toPo(MaterialVersion version, UploadSessionPO upload, String ingestState) {
        MaterialVersionPO po = new MaterialVersionPO();
        po.setId(version.id());
        po.setMaterialId(version.materialId());
        po.setOwnerKey(version.ownerKey());
        po.setVersionNo(version.versionNo());
        po.setContentBlobId(version.contentBlobId());
        po.setContentSha256(version.contentSha256());
        po.setDeclaredMime(upload.getDeclaredMime());
        po.setDetectedMime(upload.getDetectedMime());
        po.setByteSize(version.byteSize());
        po.setPageCount(upload.getPageCount());
        po.setIngestState(ingestState);
        return po;
    }

    private static ProcessingRevisionPO toPo(ProcessingRevision revision,
                                             ProcessingRevisionProfile processingProfile) {
        ProcessingRevisionPO po = new ProcessingRevisionPO();
        po.setId(revision.id());
        po.setVersionId(revision.versionId());
        po.setRevisionNo(revision.revisionNo());
        po.setFingerprint(revision.processingFingerprint());
        po.setWorkerProfileFingerprint(processingProfile.fingerprint());
        po.setState(revision.state().name());
        po.setStage(revision.stage().name());
        po.setProgress(revision.progress());
        po.setParserVersion(processingProfile.parserVersion());
        po.setCleanerVersion(processingProfile.cleanerVersion());
        po.setChunkSchemaVersion(processingProfile.chunkSchemaVersion());
        po.setOcrVersion(processingProfile.ocrVersion());
        po.setVlmSchemaVersion(processingProfile.vlmSchemaVersion());
        po.setExcludedPagesJson("[]");
        return po;
    }

    private static ProcessingJobPO toPo(ProcessingJob job) {
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

    private static ProcessingJobPO extractionPo(ProcessingJob job, String sourceSha256,
                                                 String processingFingerprint) {
        ProcessingJobPO po = toPo(job);
        po.setInputFingerprint(ProcessingStageFingerprintPolicy.extractionInput(
                sourceSha256, processingFingerprint));
        return po;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
