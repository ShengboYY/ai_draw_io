package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.NativePageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageStatus;
import org.zipp.ai.domain.ingestion.model.valobj.PageArtifactKind;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionExtractionWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageBatch;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageWork;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.DocumentExtractionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionPageWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingGenerationPO;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Repository
public class MySqlDocumentProcessingWorkAdapter implements DocumentProcessingWorkPort {

    private final IDocumentProcessingMapper mapper;
    private final IProcessingJobMapper jobMapper;

    public MySqlDocumentProcessingWorkAdapter(IDocumentProcessingMapper mapper, IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    @Override
    public Optional<RevisionExtractionWork> findExtractionWork(String revisionId, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        return Optional.ofNullable(mapper.selectExtractionWork(requireText(revisionId, "revisionId"),
                current.jobId(), current.workerId(), current.fenceToken())).map(this::toDomain);
    }

    @Override
    @Transactional
    public boolean commitNativeExtraction(RevisionExtractionWork work, List<NativePageResult> pages,
                                          List<ProcessingJob> nextJobs, WorkerFence fence) {
        RevisionExtractionWork source = Objects.requireNonNull(work, "work");
        List<NativePageResult> outputs = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("native extraction requires pages");
        }
        List<ProcessingJob> successors = List.copyOf(Objects.requireNonNull(nextJobs, "nextJobs"));
        if (successors.size() != outputs.size()) {
            throw new IllegalArgumentException("every extracted page requires one successor job");
        }
        for (NativePageResult page : outputs) {
            ProcessingJobStage expectedStage = page.ocrRequired()
                    ? ProcessingJobStage.OCR_SELECTED_PAGES : ProcessingJobStage.NORMALIZE_CANONICAL_PAGES;
            long matchingJobs = successors.stream().filter(job -> job.stage() == expectedStage
                    && ("page:" + page.pageNo()).equals(job.workKey())).count();
            if (matchingJobs != 1) {
                throw new IllegalArgumentException("each page requires one correctly staged successor");
            }
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.EXTRACT_NATIVE, fence)) {
            return false;
        }
        for (NativePageResult output : outputs) {
            MaterialPagePO page = new MaterialPagePO();
            page.setId("page_" + UUID.randomUUID());
            page.setRevisionId(source.revisionId());
            page.setPageNo(output.pageNo());
            page.setWidth(output.width());
            page.setHeight(output.height());
            page.setNativeTextStatus("EXTRACTED");
            page.setOcrStatus(output.ocrRequired() ? "REQUIRED" : "NOT_REQUIRED");
            page.setVisualStatus("PENDING");
            page.setPageImageKey(output.pageImage().objectKey());
            page.setRawExtractionKey(output.rawExtraction() == null ? null : output.rawExtraction().objectKey());
            mapper.insertPage(page);
            assertPageIdentity(page);
            persistArtifact(source.revisionId(), output.pageNo(), PageArtifactKind.PAGE_IMAGE, output.pageImage());
            persistArtifact(source.revisionId(), output.pageNo(), PageArtifactKind.NATIVE_EXTRACTION,
                    output.nativeExtraction());
            if (output.rawExtraction() != null) {
                persistArtifact(source.revisionId(), output.pageNo(), PageArtifactKind.RAW_EXTRACTION,
                        output.rawExtraction());
            }
        }
        mapper.updatePageCount(source.revisionId(), outputs.size());
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                "EXTRACTING", 35) != 1) {
            throw new IllegalStateException("revision generation became stale during native extraction");
        }
        successors.forEach(job -> jobMapper.insert(toPo(job)));
        return true;
    }

    @Override
    public Optional<RevisionPageBatch> findPageBatch(String revisionId, WorkerFence fence) {
        String id = requireText(revisionId, "revisionId");
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        ProcessingGenerationPO generations = mapper.selectProcessingGenerations(id, current.jobId(), current.workerId(),
                current.fenceToken());
        if (generations == null) {
            return Optional.empty();
        }
        List<RevisionPageWork> pages = mapper.selectPageBatch(id, current.jobId(), current.workerId(),
                current.fenceToken()).stream().map(this::toDomain).toList();
        return pages.isEmpty() ? Optional.empty() : Optional.of(new RevisionPageBatch(id,
                generations.getRevisionFenceGeneration(), generations.getMaterialLifecycleGeneration(),
                generations.getProcessingFingerprint(), pages));
    }

    @Override
    @Transactional
    public boolean commitOcr(RevisionPageBatch batch, List<OcrPageResult> pages,
                             ProcessingJob nextJob, WorkerFence fence) {
        RevisionPageBatch source = Objects.requireNonNull(batch, "batch");
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.OCR_SELECTED_PAGES, fence)) {
            return false;
        }
        List<OcrPageResult> outputs = List.copyOf(Objects.requireNonNull(pages, "pages"));
        long requiredPageCount = source.pages().stream().filter(RevisionPageWork::requiresOcr).count();
        if (outputs.size() != requiredPageCount) {
            throw new IllegalArgumentException("every selected OCR page requires a result");
        }
        for (OcrPageResult output : outputs) {
            persistArtifact(source.revisionId(), output.pageNo(), PageArtifactKind.RAW_EXTRACTION,
                    output.rawExtraction());
            if (mapper.updatePageOcr(source.revisionId(), output.pageNo(),
                    output.lowConfidence() ? "LOW_CONFIDENCE" : "COMPLETED",
                    output.meanConfidence(), output.rawExtraction().objectKey()) != 1) {
                throw new IllegalStateException("OCR page state was not selectable");
            }
        }
        if (mapper.updateRevisionProgress(source.revisionId(), "OCR_VISUAL", 55) != 1) {
            throw new IllegalStateException("revision was unavailable during OCR progress update");
        }
        jobMapper.insert(toPo(nextJob));
        return true;
    }

    @Override
    @Transactional
    public boolean commitCanonical(RevisionPageBatch batch, List<CanonicalPageResult> pages,
                                   ProcessingJob nextJob, WorkerFence fence) {
        RevisionPageBatch source = Objects.requireNonNull(batch, "batch");
        List<CanonicalPageResult> outputs = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (outputs.size() != source.pages().size()) {
            throw new IllegalArgumentException("every page requires a canonical artifact");
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.NORMALIZE_CANONICAL_PAGES, fence)) {
            return false;
        }
        for (CanonicalPageResult output : outputs) {
            persistArtifact(source.revisionId(), output.pageNo(), PageArtifactKind.CANONICAL_PAGE,
                    output.canonicalPage());
            if (mapper.updatePageCanonical(source.revisionId(), output.pageNo(),
                    output.canonicalPage().objectKey()) != 1) {
                throw new IllegalStateException("canonical page state was not selectable");
            }
        }
        if (mapper.updateRevisionProgress(source.revisionId(), "OCR_VISUAL", 65) != 1) {
            throw new IllegalStateException("revision was unavailable during canonical progress update");
        }
        if (mapper.countMissingCanonical(source.revisionId()) > 0) {
            return true;
        }
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                "OCR_VISUAL", 65) != 1) {
            throw new IllegalStateException("revision generation became stale at canonical barrier");
        }
        ProcessingJobPO successor = toPo(nextJob);
        successor.setInputFingerprint(sha256(String.join(":", mapper.selectCanonicalHashes(source.revisionId()))
                + ":" + nextJob.inputFingerprint()));
        jobMapper.insert(successor);
        return true;
    }

    private boolean hasCurrentFence(String revisionId, long expectedGeneration, long materialLifecycleGeneration,
                                    ProcessingJobStage expectedStage, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        return mapper.countCurrentFence(revisionId, expectedGeneration, materialLifecycleGeneration,
                expectedStage.name(), current.jobId(), current.workerId(), current.fenceToken()) == 1;
    }

    private void assertPageIdentity(MaterialPagePO expected) {
        MaterialPagePO persisted = mapper.selectPage(expected.getRevisionId(), expected.getPageNo());
        if (persisted == null || Math.abs(expected.getWidth() - persisted.getWidth()) > 0.0001
                || Math.abs(expected.getHeight() - persisted.getHeight()) > 0.0001
                || !expected.getNativeTextStatus().equals(persisted.getNativeTextStatus())
                || !expected.getOcrStatus().equals(persisted.getOcrStatus())
                || !expected.getPageImageKey().equals(persisted.getPageImageKey())
                || !Objects.equals(expected.getRawExtractionKey(), persisted.getRawExtractionKey())) {
            throw new IllegalStateException("immutable material page collided with different facts");
        }
    }

    private void persistArtifact(String revisionId, int pageNo, PageArtifactKind kind, StoredArtifact artifact) {
        RevisionArtifactPO po = new RevisionArtifactPO();
        po.setId("artifact_" + UUID.randomUUID());
        po.setRevisionId(revisionId);
        po.setPageNo(pageNo);
        po.setArtifactKind(kind.name());
        po.setObjectKey(artifact.objectKey());
        po.setObjectVersionId(artifact.objectVersionId());
        po.setContentSha256(artifact.contentSha256());
        po.setByteSize(artifact.byteSize());
        po.setContentType(artifact.contentType());
        mapper.insertArtifact(po);
        RevisionArtifactPO persisted = mapper.selectArtifact(revisionId, pageNo, kind.name());
        if (persisted == null || !artifact.objectKey().equals(persisted.getObjectKey())
                || !artifact.objectVersionId().equals(persisted.getObjectVersionId())
                || !artifact.contentSha256().equals(persisted.getContentSha256())
                || artifact.byteSize() != persisted.getByteSize()) {
            throw new IllegalStateException("immutable page artifact collided with different content");
        }
    }

    private RevisionExtractionWork toDomain(DocumentExtractionWorkPO po) {
        return new RevisionExtractionWork(po.getRevisionId(), po.getDetectedMediaType(),
                po.getRevisionFenceGeneration(), po.getMaterialLifecycleGeneration(), po.getProcessingFingerprint(),
                new StoredArtifact(po.getObjectKey(), po.getObjectVersionId(),
                po.getContentSha256(), po.getByteSize(), po.getContentType()));
    }

    private RevisionPageWork toDomain(RevisionPageWorkPO po) {
        StoredArtifact image = new StoredArtifact(po.getPageImageKey(), po.getPageImageVersionId(),
                po.getPageImageSha256(), po.getPageImageSize(), po.getPageImageContentType());
        StoredArtifact nativeExtraction = new StoredArtifact(po.getNativeKey(), po.getNativeVersionId(),
                po.getNativeSha256(), po.getNativeSize(), po.getNativeContentType());
        StoredArtifact raw = po.getRawKey() == null ? null : new StoredArtifact(po.getRawKey(),
                po.getRawVersionId(), po.getRawSha256(), po.getRawSize(), po.getRawContentType());
        return new RevisionPageWork(po.getPageNo(), po.getWidth(), po.getHeight(),
                OcrPageStatus.valueOf(po.getOcrStatus()),
                image, nativeExtraction, raw);
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
