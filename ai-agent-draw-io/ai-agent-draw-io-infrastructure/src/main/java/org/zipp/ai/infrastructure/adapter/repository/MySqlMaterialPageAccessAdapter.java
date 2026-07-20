package org.zipp.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingStage;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.infrastructure.dao.material.IMaterialPreviewMapper;
import org.zipp.ai.infrastructure.dao.material.IMaterializationMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.util.*;

/** Owner-fenced MySQL adapter for immutable page views and processing revisions. */
@Repository
public class MySqlMaterialPageAccessAdapter implements MaterialPageAccessPort {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IMaterialPreviewMapper mapper;
    private final IMaterializationMapper materializationMapper;
    private final IProcessingJobMapper jobMapper;

    public MySqlMaterialPageAccessAdapter(IMaterialPreviewMapper mapper,
                                          IMaterializationMapper materializationMapper,
                                          IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.materializationMapper = Objects.requireNonNull(materializationMapper,
                "materializationMapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    @Override
    public Optional<MaterialPageSet> findPages(CatalogOwner owner, String materialId,
                                               String versionId, String revisionId) {
        List<MaterialPageCatalogPO> rows = mapper.selectPages(owner.ownerType().name(), owner.ownerKey(),
                materialId, versionId, revisionId);
        if (rows.isEmpty()) return Optional.empty();
        MaterialPageCatalogPO header = rows.get(0);
        List<MaterialPageSummary> pages = rows.stream()
                .filter(row -> row.getPageNo() != null)
                .map(this::page)
                .toList();
        return Optional.of(new MaterialPageSet(header.getMaterialId(), header.getVersionId(),
                header.getRevisionId(), header.getRevisionNo(),
                CatalogProcessingStatus.from(header.getIngestState(), header.getRevisionState(),
                        header.getRevisionStage()), header.getProgress(),
                excludedPages(header.getExcludedPagesJson()), pages));
    }

    @Override
    public Optional<StoredArtifact> findPreviewArtifact(CatalogOwner owner, String materialId,
                                                        String versionId, String revisionId, int pageNo) {
        MaterialPreviewArtifactPO po = mapper.selectPreviewArtifact(owner.ownerType().name(),
                owner.ownerKey(), materialId, versionId, revisionId, pageNo);
        if (po == null) return Optional.empty();
        return Optional.of(new StoredArtifact(po.getObjectKey(), po.getObjectVersionId(),
                po.getContentSha256(), po.getByteSize(), po.getContentType()));
    }

    @Override
    public Optional<MaterialReprocessSnapshot> findReprocessSnapshot(CatalogOwner owner,
                                                                     String materialId) {
        return Optional.ofNullable(mapper.selectReprocessSource(owner.ownerType().name(),
                owner.ownerKey(), materialId)).map(this::snapshot);
    }

    @Override
    @Transactional
    public MaterialReprocessResult createOrFindRevision(MaterialReprocessPlan plan) {
        MaterialReprocessSourcePO locked = mapper.selectReprocessSourceForUpdate(
                plan.owner().ownerType().name(), plan.owner().ownerKey(), plan.materialId());
        if (locked == null) throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_FOUND);

        // Request identity is material-scoped, so a retry remains stable after the latest version changes.
        MaterialReprocessResultPO existing = mapper.selectRequest(plan.owner().ownerKey(),
                plan.materialId(), plan.requestFingerprint());
        if (existing != null) return result(existing, true);

        ProcessingRevision domainRevision = plan.revision();
        if (!domainRevision.versionId().equals(locked.getVersionId())
                || !plan.expectedLatestRevisionId().equals(locked.getLatestRevisionId())) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        MaterialReprocessResultPO sameProcessing = mapper.selectByProcessingFingerprint(
                plan.owner().ownerKey(), domainRevision.versionId(), domainRevision.processingFingerprint());
        if (sameProcessing != null) {
            if ("FAILED".equals(sameProcessing.getRevisionState())) {
                retryFailedRevision(plan, sameProcessing);
                sameProcessing = mapper.selectReprocessResult(plan.owner().ownerKey(),
                        sameProcessing.getRevisionId());
                if (sameProcessing == null) {
                    throw new IllegalStateException("retried revision was not persisted");
                }
            }
            persistRequest(plan, sameProcessing.getVersionId(), sameProcessing.getRevisionId());
            return result(sameProcessing, true);
        }
        if ("PROCESSING".equals(locked.getLatestRevisionState())) {
            throw new CatalogOperationException(CatalogErrorCode.REPROCESS_IN_PROGRESS);
        }

        if (materializationMapper.insertRevision(revisionPo(domainRevision,
                plan.processingProfile())) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }

        if (jobMapper.insert(jobPo(plan.extractionJob())) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        persistRequest(plan, domainRevision.versionId(), domainRevision.id());
        MaterialReprocessResultPO persisted = mapper.selectReprocessResult(plan.owner().ownerKey(),
                domainRevision.id());
        if (persisted == null) throw new IllegalStateException("reprocess revision was not persisted");
        return result(persisted, false);
    }

    private void retryFailedRevision(MaterialReprocessPlan plan, MaterialReprocessResultPO persisted) {
        ProcessingRevision revision = ProcessingRevision.rehydrateFailed(persisted.getRevisionId(),
                persisted.getVersionId(), persisted.getRevisionNo(),
                persisted.getProcessingFingerprint(), excludedPages(persisted.getExcludedPagesJson()),
                ProcessingStage.valueOf(persisted.getRevisionStage()), persisted.getProgress(),
                persisted.getErrorCode());
        revision.retryFailed();
        // The failed stage is resumed; successful predecessor jobs and artifacts remain immutable.
        if (mapper.restartFailedRevision(revision.id(), revision.state().name()) != 1
                || jobMapper.requeueFailedByRevision(revision.id(), plan.requestedAt()) < 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
    }

    private MaterialPageSummary page(MaterialPageCatalogPO po) {
        return new MaterialPageSummary(po.getPageNo(), po.getWidth(), po.getHeight(),
                po.getNativeTextStatus(), po.getOcrStatus(), po.getOcrQuality(), po.getVisualStatus(),
                po.getErrorCode(), po.getCanonicalAvailable() == 1, po.getPreviewAvailable() == 1);
    }

    private MaterialReprocessSnapshot snapshot(MaterialReprocessSourcePO po) {
        return new MaterialReprocessSnapshot(po.getMaterialId(), po.getVersionId(), po.getPageCount(),
                po.getContentSha256(), po.getLatestRevisionId(), po.getLatestRevisionNo(),
                po.getLatestRevisionState(), po.getProcessingFingerprint(),
                new ProcessingRevisionProfile(po.getWorkerProfileFingerprint(), po.getParserVersion(),
                        po.getCleanerVersion(), po.getChunkSchemaVersion(), po.getOcrVersion(),
                        po.getVlmSchemaVersion()),
                excludedPages(po.getExcludedPagesJson()));
    }

    private void persistRequest(MaterialReprocessPlan plan, String versionId, String revisionId) {
        if (mapper.insertRequest(plan.owner().ownerKey(), plan.materialId(), plan.requestFingerprint(),
                versionId, revisionId) != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
    }

    private ProcessingRevisionPO revisionPo(ProcessingRevision revision,
                                              ProcessingRevisionProfile profile) {
        ProcessingRevisionPO po = new ProcessingRevisionPO();
        po.setId(revision.id());
        po.setVersionId(revision.versionId());
        po.setRevisionNo(revision.revisionNo());
        po.setFingerprint(revision.processingFingerprint());
        po.setWorkerProfileFingerprint(profile.fingerprint());
        po.setState(revision.state().name());
        po.setStage(revision.stage().name());
        po.setProgress(revision.progress());
        po.setParserVersion(profile.parserVersion());
        po.setCleanerVersion(profile.cleanerVersion());
        po.setChunkSchemaVersion(profile.chunkSchemaVersion());
        po.setOcrVersion(profile.ocrVersion());
        po.setVlmSchemaVersion(profile.vlmSchemaVersion());
        po.setExcludedPagesJson(excludedPagesJson(revision.excludedPages()));
        return po;
    }

    private ProcessingJobPO jobPo(ProcessingJob job) {
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

    private MaterialReprocessResult result(MaterialReprocessResultPO po, boolean reused) {
        return new MaterialReprocessResult(po.getMaterialId(), po.getVersionId(), po.getRevisionId(),
                po.getRevisionNo(), CatalogProcessingStatus.from(po.getIngestState(),
                po.getRevisionState(), po.getRevisionStage()), reused);
    }

    private Set<Integer> excludedPages(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Set.copyOf(JSON.readValue(value, new TypeReference<List<Integer>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid excluded_pages_json", e);
        }
    }

    private String excludedPagesJson(Set<Integer> pages) {
        try {
            return JSON.writeValueAsString(new TreeSet<>(pages));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("excluded pages cannot be serialized", e);
        }
    }
}
