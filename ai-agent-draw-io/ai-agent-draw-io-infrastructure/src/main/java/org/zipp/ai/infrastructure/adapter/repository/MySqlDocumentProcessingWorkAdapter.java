package org.zipp.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentSection;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructureResult;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceBuildResult;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceRegion;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceRelation;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceUnit;
import org.zipp.ai.domain.ingestion.model.valobj.NativePageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageStatus;
import org.zipp.ai.domain.ingestion.model.valobj.PageArtifactKind;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingStage;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionExtractionWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionEvidenceWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionRetrievalWork;
import org.zipp.ai.domain.ingestion.model.valobj.RetrievalBuildResult;
import org.zipp.ai.domain.ingestion.model.valobj.RetrievalChunkArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageBatch;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionCanonicalPageWork;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionStructureWork;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionVisualWork;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.VisualProcessingResult;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.retrieval.projection.LexicalProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalEvidenceMapping;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.DocumentExtractionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionPageWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingGenerationPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionStructurePagePO;
import org.zipp.ai.infrastructure.dao.material.po.DocumentStructureArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialSectionPO;
import org.zipp.ai.infrastructure.dao.material.po.VisualCropArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceUnitPO;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceRegionPO;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceRelationPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionRetrievalWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.RetrievalChunkPO;
import org.zipp.ai.infrastructure.dao.material.po.RetrievalChunkEvidencePO;
import org.zipp.ai.infrastructure.dao.material.po.RetrievalSearchDocumentPO;
import org.zipp.ai.infrastructure.dao.material.po.RetrievalExactTermPO;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Repository
public class MySqlDocumentProcessingWorkAdapter implements DocumentProcessingWorkPort {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        if (nextJob.stage() != ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE
                || !"root".equals(nextJob.workKey())
                || !source.revisionId().equals(nextJob.target().revisionId())
                || !ProcessingStageFingerprintPolicy.structureSeed(source.processingFingerprint())
                        .equals(nextJob.inputFingerprint())) {
            throw new IllegalArgumentException("canonical barrier requires the pinned structure successor");
        }
        ProcessingJobPO successor = toPo(nextJob);
        successor.setInputFingerprint(ProcessingStageFingerprintPolicy.structureInput(
                mapper.selectCanonicalHashes(source.revisionId()), source.processingFingerprint()));
        jobMapper.insert(successor);
        return true;
    }

    @Override
    public Optional<RevisionStructureWork> findStructureWork(String revisionId, WorkerFence fence) {
        String id = requireText(revisionId, "revisionId");
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<RevisionStructurePagePO> rows = mapper.selectStructureWork(id, current.jobId(), current.workerId(),
                current.fenceToken());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RevisionStructurePagePO first = rows.get(0);
        List<RevisionCanonicalPageWork> pages = rows.stream().map(this::toStructurePage).toList();
        return Optional.of(new RevisionStructureWork(first.getRevisionId(), first.getVersionId(),
                first.getRevisionFenceGeneration(), first.getMaterialLifecycleGeneration(),
                first.getProcessingFingerprint(), pages));
    }

    @Override
    @Transactional
    public boolean commitStructure(RevisionStructureWork work, DocumentStructureResult result,
                                   ProcessingJob nextJob, WorkerFence fence) {
        RevisionStructureWork source = Objects.requireNonNull(work, "work");
        DocumentStructureResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        if (successor.stage() != ProcessingJobStage.ANALYZE_VISUALS
                || !"root".equals(successor.workKey())
                || !source.revisionId().equals(successor.target().revisionId())
                || !ProcessingStageFingerprintPolicy.visualInput(output.structure().structureHash(),
                        output.artifact().contentSha256(), source.processingFingerprint())
                        .equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("structure commit requires the pinned visual successor");
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE, fence)) {
            return false;
        }
        persistRevisionArtifact(source.revisionId(), "DOCUMENT_STRUCTURE", output.artifact());
        output.structure().sections().forEach(section -> persistSection(source.revisionId(), section));
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                "VISUAL_ANALYSIS", 75) != 1) {
            throw new IllegalStateException("revision generation became stale during structure commit");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    @Override
    public Optional<RevisionVisualWork> findVisualWork(String revisionId, WorkerFence fence) {
        String id = requireText(revisionId, "revisionId");
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<RevisionStructurePagePO> rows = mapper.selectVisualWork(id, current.jobId(), current.workerId(),
                current.fenceToken());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RevisionStructurePagePO first = rows.get(0);
        StoredArtifact structure = new StoredArtifact(first.getStructureKey(), first.getStructureVersionId(),
                first.getStructureSha256(), first.getStructureSize(), first.getStructureContentType());
        return Optional.of(new RevisionVisualWork(first.getRevisionId(), first.getVersionId(),
                first.getRevisionFenceGeneration(), first.getMaterialLifecycleGeneration(),
                first.getProcessingFingerprint(), structure, rows.stream().map(this::toStructurePage).toList()));
    }

    @Override
    @Transactional
    public boolean commitVisualCrops(RevisionVisualWork work, VisualProcessingResult result,
                                     ProcessingJob nextJob, WorkerFence fence) {
        RevisionVisualWork source = Objects.requireNonNull(work, "work");
        VisualProcessingResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        if (successor.stage() != ProcessingJobStage.BUILD_EVIDENCE_UNITS
                || !"root".equals(successor.workKey())
                || !source.revisionId().equals(successor.target().revisionId())
                || !ProcessingStageFingerprintPolicy.evidenceInput(output.manifestArtifact().contentSha256(),
                        source.processingFingerprint()).equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("visual crop commit requires the pinned evidence successor");
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.ANALYZE_VISUALS, fence)) {
            return false;
        }
        for (VisualCropArtifact crop : output.manifest().crops()) {
            persistVisualCrop(source.revisionId(), crop);
        }
        persistRevisionArtifact(source.revisionId(), "VISUAL_CROP_MANIFEST", output.manifestArtifact());
        var croppedPages = output.manifest().crops().stream()
                .map(crop -> crop.candidate().pageNo()).collect(java.util.stream.Collectors.toSet());
        for (RevisionCanonicalPageWork page : source.pages()) {
            if (mapper.updatePageVisualStatus(source.revisionId(), page.pageNo(),
                    croppedPages.contains(page.pageNo()) ? "CROPPED" : "NOT_SELECTED") != 1) {
                throw new IllegalStateException("visual page state was not selectable");
            }
        }
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                "EVIDENCE_BUILD", 82) != 1) {
            throw new IllegalStateException("revision generation became stale during visual crop commit");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    @Override
    public Optional<RevisionEvidenceWork> findEvidenceWork(String revisionId, WorkerFence fence) {
        String id = requireText(revisionId, "revisionId");
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        List<RevisionStructurePagePO> rows = mapper.selectEvidenceWork(id, current.jobId(), current.workerId(),
                current.fenceToken());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RevisionStructurePagePO first = rows.get(0);
        StoredArtifact structure = new StoredArtifact(first.getStructureKey(), first.getStructureVersionId(),
                first.getStructureSha256(), first.getStructureSize(), first.getStructureContentType());
        StoredArtifact visualManifest = new StoredArtifact(first.getVisualManifestKey(),
                first.getVisualManifestVersionId(), first.getVisualManifestSha256(),
                first.getVisualManifestSize(), first.getVisualManifestContentType());
        return Optional.of(new RevisionEvidenceWork(first.getRevisionId(), first.getVersionId(),
                first.getRevisionFenceGeneration(), first.getMaterialLifecycleGeneration(),
                first.getProcessingFingerprint(), structure, visualManifest,
                rows.stream().map(this::toStructurePage).toList()));
    }

    @Override
    @Transactional
    public boolean commitEvidence(RevisionEvidenceWork work, EvidenceBuildResult result,
                                  ProcessingJob nextJob, WorkerFence fence) {
        RevisionEvidenceWork source = Objects.requireNonNull(work, "work");
        EvidenceBuildResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        if (successor.stage() != ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS
                || !"root".equals(successor.workKey())
                || !source.revisionId().equals(successor.target().revisionId())
                || !ProcessingStageFingerprintPolicy.retrievalInput(output.manifestArtifact().contentSha256(),
                        source.processingFingerprint()).equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("evidence commit requires the pinned retrieval successor");
        }
        if (!source.revisionId().equals(output.manifest().revisionId())
                || !source.versionId().equals(output.manifest().versionId())) {
            throw new IllegalArgumentException("evidence manifest does not belong to its work target");
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.BUILD_EVIDENCE_UNITS, fence)) {
            return false;
        }
        persistRevisionArtifact(source.revisionId(), "EVIDENCE_MANIFEST", output.manifestArtifact());
        output.manifest().units().forEach(unit -> persistEvidenceUnit(source,
                output.displayArtifacts().getOrDefault(unit.evidenceId(), output.manifestArtifact()), unit));
        output.manifest().relations().forEach(this::persistEvidenceRelation);
        output.manifest().sectionHeadings().forEach(heading -> {
            mapper.updateSectionHeading(source.revisionId(), heading.sectionId(), heading.evidenceId());
            MaterialSectionPO section = mapper.selectSectionById(source.revisionId(), heading.sectionId());
            if (section == null || !heading.evidenceId().equals(section.getHeadingEvidenceId())) {
                throw new IllegalStateException("section heading evidence collided with a different unit");
            }
        });
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                "INDEXING", 88) != 1) {
            throw new IllegalStateException("revision generation became stale during evidence commit");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    @Override
    public Optional<RevisionRetrievalWork> findRetrievalWork(String revisionId, WorkerFence fence) {
        WorkerFence current = Objects.requireNonNull(fence, "fence");
        RevisionRetrievalWorkPO po = mapper.selectRetrievalWork(requireText(revisionId, "revisionId"),
                current.jobId(), current.workerId(), current.fenceToken());
        if (po == null) {
            return Optional.empty();
        }
        StoredArtifact evidence = new StoredArtifact(po.getEvidenceManifestKey(),
                po.getEvidenceManifestVersionId(), po.getEvidenceManifestSha256(),
                po.getEvidenceManifestSize(), po.getEvidenceManifestContentType());
        return Optional.of(new RevisionRetrievalWork(po.getRevisionId(), po.getVersionId(), po.getMaterialId(),
                OwnerType.valueOf(po.getOwnerType()), po.getOwnerKey(), po.getRevisionFenceGeneration(),
                po.getMaterialLifecycleGeneration(), po.getProcessingFingerprint(), evidence));
    }

    @Override
    @Transactional
    public boolean commitRetrieval(RevisionRetrievalWork work, RetrievalBuildResult result,
                                   ProcessingJob nextJob, WorkerFence fence) {
        RevisionRetrievalWork source = Objects.requireNonNull(work, "work");
        RetrievalBuildResult output = Objects.requireNonNull(result, "result");
        ProcessingJob successor = Objects.requireNonNull(nextJob, "nextJob");
        if (!source.revisionId().equals(output.manifest().revisionId())
                || !source.versionId().equals(output.manifest().versionId())) {
            throw new IllegalArgumentException("retrieval manifest does not belong to its work target");
        }
        if (successor.stage() != ProcessingJobStage.BUILD_LEXICAL_PROJECTION
                || !"root".equals(successor.workKey())
                || !source.revisionId().equals(successor.target().revisionId())
                || !ProcessingStageFingerprintPolicy.lexicalProjectionInput(
                        output.manifestArtifact().contentSha256(),
                        source.processingFingerprint()).equals(successor.inputFingerprint())) {
            throw new IllegalArgumentException("retrieval commit requires the pinned projection coordinator");
        }
        if (!hasCurrentFence(source.revisionId(), source.revisionFenceGeneration(),
                source.materialLifecycleGeneration(), ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS, fence)) {
            return false;
        }
        Map<String, RetrievalChunkArtifact> artifactsByChunk = output.chunkArtifacts().stream()
                .collect(Collectors.toUnmodifiableMap(RetrievalChunkArtifact::chunkId, Function.identity()));
        Map<String, LexicalProjection> lexicalByChunk = output.manifest().lexicalProjections().stream()
                .collect(Collectors.toUnmodifiableMap(LexicalProjection::chunkId, Function.identity()));
        persistRevisionArtifact(source.revisionId(), "RETRIEVAL_MANIFEST", output.manifestArtifact());
        for (RetrievalChunkProjection chunk : output.manifest().chunks()) {
            RetrievalChunkArtifact chunkArtifact = artifactsByChunk.get(chunk.chunkId());
            if ((chunk.parentContext() == null) != (chunkArtifact.parentContextArtifact() == null)) {
                throw new IllegalArgumentException("parent context and its exact artifact pin must be paired");
            }
            persistRetrievalChunk(source, chunk, chunkArtifact);
            chunk.evidenceMappings().forEach(mapping -> persistRetrievalEvidence(chunk.chunkId(), mapping));
            LexicalProjection lexical = lexicalByChunk.get(chunk.chunkId());
            if (lexical != null) {
                persistSearchDocument(source, lexical);
                lexical.exactTerms().forEach(term -> persistExactTerm(source, lexical.chunkId(), term));
            }
        }
        if (mapper.advanceRevision(source.revisionId(), source.revisionFenceGeneration(),
                ProcessingStage.INDEXING.name(), 93) != 1) {
            throw new IllegalStateException("revision generation became stale during retrieval commit");
        }
        jobMapper.insert(toPo(successor));
        return true;
    }

    private void persistRetrievalChunk(RevisionRetrievalWork source, RetrievalChunkProjection chunk,
                                       RetrievalChunkArtifact artifact) {
        RetrievalChunkPO po = new RetrievalChunkPO();
        po.setId(chunk.chunkId());
        po.setVersionId(source.versionId());
        po.setRevisionId(source.revisionId());
        po.setPageId(chunk.pageId());
        po.setSectionId(chunk.sectionId());
        po.setChunkType(chunk.chunkType().name());
        po.setModality(chunk.modality().name());
        po.setLanguagePrimary(chunk.languagePrimary());
        po.setCitable(chunk.citable());
        po.setIndexMode(chunk.indexMode().name());
        po.setRetrievalTextObjectKey(artifact.retrievalTextArtifact().objectKey());
        po.setRetrievalTextObjectVersionId(artifact.retrievalTextArtifact().objectVersionId());
        po.setRetrievalTextSha256(chunk.retrievalTextSha256());
        po.setRetrievalTextByteSize(artifact.retrievalTextArtifact().byteSize());
        po.setRetrievalTextContentType(artifact.retrievalTextArtifact().contentType());
        if (artifact.parentContextArtifact() != null) {
            po.setParentContextObjectKey(artifact.parentContextArtifact().objectKey());
            po.setParentContextObjectVersionId(artifact.parentContextArtifact().objectVersionId());
        }
        po.setTokenCount(chunk.tokenCount());
        po.setQualityScore(chunk.quality());
        po.setStructuralOrdinal(chunk.structuralOrdinal());
        po.setStatus("ACTIVE");
        mapper.insertRetrievalChunk(po);
        RetrievalChunkPO persisted = mapper.selectRetrievalChunk(po.getId());
        if (persisted == null || !po.getVersionId().equals(persisted.getVersionId())
                || !po.getRevisionId().equals(persisted.getRevisionId())
                || !Objects.equals(po.getPageId(), persisted.getPageId())
                || !Objects.equals(po.getSectionId(), persisted.getSectionId())
                || !po.getChunkType().equals(persisted.getChunkType())
                || !po.getModality().equals(persisted.getModality())
                || !po.getLanguagePrimary().equals(persisted.getLanguagePrimary())
                || po.isCitable() != persisted.isCitable()
                || !po.getIndexMode().equals(persisted.getIndexMode())
                || !po.getRetrievalTextObjectKey().equals(persisted.getRetrievalTextObjectKey())
                || !po.getRetrievalTextObjectVersionId().equals(persisted.getRetrievalTextObjectVersionId())
                || !po.getRetrievalTextSha256().equals(persisted.getRetrievalTextSha256())
                || !Objects.equals(po.getParentContextObjectKey(), persisted.getParentContextObjectKey())
                || !Objects.equals(po.getParentContextObjectVersionId(),
                        persisted.getParentContextObjectVersionId())
                || po.getTokenCount() != persisted.getTokenCount()
                || Math.abs(po.getQualityScore() - persisted.getQualityScore()) > 0.000001
                || po.getStructuralOrdinal() != persisted.getStructuralOrdinal()
                || !po.getStatus().equals(persisted.getStatus())) {
            throw new IllegalStateException("immutable retrieval chunk collided with different content");
        }
    }

    private void persistRetrievalEvidence(String chunkId, RetrievalEvidenceMapping mapping) {
        RetrievalChunkEvidencePO po = new RetrievalChunkEvidencePO();
        po.setRetrievalChunkId(chunkId);
        po.setEvidenceId(mapping.evidenceId());
        po.setRole(mapping.role().name());
        po.setOrdinal(mapping.ordinal());
        po.setCharStart(mapping.charStart());
        po.setCharEnd(mapping.charEnd());
        mapper.insertRetrievalChunkEvidence(po);
        RetrievalChunkEvidencePO persisted = mapper.selectRetrievalChunkEvidence(chunkId, mapping.ordinal());
        if (persisted == null || !po.getEvidenceId().equals(persisted.getEvidenceId())
                || !po.getRole().equals(persisted.getRole())
                || !Objects.equals(po.getCharStart(), persisted.getCharStart())
                || !Objects.equals(po.getCharEnd(), persisted.getCharEnd())) {
            throw new IllegalStateException("immutable retrieval Evidence mapping collided with different content");
        }
    }

    private void persistSearchDocument(RevisionRetrievalWork source, LexicalProjection lexical) {
        RetrievalSearchDocumentPO po = new RetrievalSearchDocumentPO();
        po.setRetrievalChunkId(lexical.chunkId());
        po.setOwnerType(source.ownerType().name());
        po.setOwnerKey(source.ownerKey());
        po.setMaterialId(source.materialId());
        po.setVersionId(source.versionId());
        po.setRevisionId(source.revisionId());
        po.setWordSearchText(lexical.wordSearchText());
        po.setCjkSearchText(lexical.cjkSearchText());
        po.setStatus("ACTIVE");
        mapper.insertRetrievalSearchDocument(po);
        RetrievalSearchDocumentPO persisted = mapper.selectRetrievalSearchDocument(lexical.chunkId());
        if (persisted == null || !po.getOwnerType().equals(persisted.getOwnerType())
                || !po.getOwnerKey().equals(persisted.getOwnerKey())
                || !po.getMaterialId().equals(persisted.getMaterialId())
                || !po.getVersionId().equals(persisted.getVersionId())
                || !po.getRevisionId().equals(persisted.getRevisionId())
                || !Objects.equals(po.getWordSearchText(), persisted.getWordSearchText())
                || !Objects.equals(po.getCjkSearchText(), persisted.getCjkSearchText())
                || !po.getStatus().equals(persisted.getStatus())) {
            throw new IllegalStateException("immutable retrieval search document collided with different content");
        }
    }

    private void persistExactTerm(RevisionRetrievalWork source, String chunkId, LexicalProjection.ExactTerm term) {
        RetrievalExactTermPO po = new RetrievalExactTermPO();
        po.setRetrievalChunkId(chunkId);
        po.setOwnerType(source.ownerType().name());
        po.setOwnerKey(source.ownerKey());
        po.setVersionId(source.versionId());
        po.setRevisionId(source.revisionId());
        po.setNormalizedTerm(term.normalizedTerm());
        po.setTermType(term.termType());
        mapper.insertRetrievalExactTerm(po);
        RetrievalExactTermPO persisted = mapper.selectRetrievalExactTerm(
                chunkId, term.normalizedTerm(), term.termType());
        if (persisted == null || !po.getOwnerType().equals(persisted.getOwnerType())
                || !po.getOwnerKey().equals(persisted.getOwnerKey())
                || !po.getVersionId().equals(persisted.getVersionId())
                || !po.getRevisionId().equals(persisted.getRevisionId())) {
            throw new IllegalStateException("immutable retrieval exact term collided with different content");
        }
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
                || artifact.byteSize() != persisted.getByteSize()
                || !artifact.contentType().equals(persisted.getContentType())) {
            throw new IllegalStateException("immutable page artifact collided with different content");
        }
    }

    private RevisionExtractionWork toDomain(DocumentExtractionWorkPO po) {
        return new RevisionExtractionWork(po.getRevisionId(), po.getDetectedMediaType(),
                po.getRevisionFenceGeneration(), po.getMaterialLifecycleGeneration(), po.getProcessingFingerprint(),
                excludedPages(po.getExcludedPagesJson()),
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

    private RevisionCanonicalPageWork toStructurePage(RevisionStructurePagePO po) {
        StoredArtifact image = new StoredArtifact(po.getPageImageKey(), po.getPageImageVersionId(),
                po.getPageImageSha256(), po.getPageImageSize(), po.getPageImageContentType());
        StoredArtifact canonical = new StoredArtifact(po.getCanonicalKey(), po.getCanonicalVersionId(),
                po.getCanonicalSha256(), po.getCanonicalSize(), po.getCanonicalContentType());
        return new RevisionCanonicalPageWork(po.getPageId(), po.getPageNo(), image, canonical);
    }

    private void persistRevisionArtifact(String revisionId, String artifactKind, StoredArtifact artifact) {
        DocumentStructureArtifactPO po = new DocumentStructureArtifactPO();
        po.setId("revision_artifact_" + UUID.randomUUID());
        po.setRevisionId(revisionId);
        po.setArtifactKind(artifactKind);
        po.setObjectKey(artifact.objectKey());
        po.setObjectVersionId(artifact.objectVersionId());
        po.setContentSha256(artifact.contentSha256());
        po.setByteSize(artifact.byteSize());
        po.setContentType(artifact.contentType());
        mapper.insertRevisionArtifact(po);
        DocumentStructureArtifactPO persisted = mapper.selectRevisionArtifact(revisionId, artifactKind);
        if (persisted == null || !artifact.objectKey().equals(persisted.getObjectKey())
                || !artifact.objectVersionId().equals(persisted.getObjectVersionId())
                || !artifact.contentSha256().equals(persisted.getContentSha256())
                || artifact.byteSize() != persisted.getByteSize()
                || !artifact.contentType().equals(persisted.getContentType())) {
            throw new IllegalStateException("immutable revision artifact collided with different content");
        }
    }

    private void persistVisualCrop(String revisionId, VisualCropArtifact crop) {
        VisualCropArtifactPO po = new VisualCropArtifactPO();
        po.setRevisionId(revisionId);
        po.setCandidateId(crop.candidate().candidateId());
        po.setPageId(crop.pageId());
        po.setPageNo(crop.candidate().pageNo());
        po.setCaptionBlockId(crop.candidate().captionBlockId());
        po.setObjectKey(crop.artifact().objectKey());
        po.setObjectVersionId(crop.artifact().objectVersionId());
        po.setContentSha256(crop.artifact().contentSha256());
        po.setByteSize(crop.artifact().byteSize());
        po.setContentType(crop.artifact().contentType());
        mapper.insertVisualCrop(po);
        VisualCropArtifactPO persisted = mapper.selectVisualCrop(revisionId, po.getCandidateId());
        if (persisted == null || !po.getPageId().equals(persisted.getPageId())
                || po.getPageNo() != persisted.getPageNo()
                || !Objects.equals(po.getCaptionBlockId(), persisted.getCaptionBlockId())
                || !po.getObjectKey().equals(persisted.getObjectKey())
                || !po.getObjectVersionId().equals(persisted.getObjectVersionId())
                || !po.getContentSha256().equals(persisted.getContentSha256())
                || po.getByteSize() != persisted.getByteSize()
                || !po.getContentType().equals(persisted.getContentType())) {
            throw new IllegalStateException("immutable visual crop collided with different content");
        }
    }

    private void persistEvidenceUnit(RevisionEvidenceWork source, StoredArtifact displayArtifact,
                                     EvidenceUnit evidence) {
        EvidenceUnitPO po = new EvidenceUnitPO();
        po.setId(evidence.evidenceId());
        po.setVersionId(source.versionId());
        po.setRevisionId(source.revisionId());
        po.setPageId(evidence.pageId());
        po.setSectionId(evidence.sectionId());
        po.setUnitType(evidence.unitType().name());
        po.setModality(evidence.modality().name());
        po.setSourceChannel(evidence.sourceChannel());
        if (evidence.displayText() != null) {
            po.setDisplayTextObjectKey(displayArtifact.objectKey());
            po.setDisplayTextObjectVersionId(displayArtifact.objectVersionId());
        }
        if (evidence.visualArtifact() != null) {
            po.setVisualObjectKey(evidence.visualArtifact().objectKey());
            po.setVisualObjectVersionId(evidence.visualArtifact().objectVersionId());
        }
        po.setDisplayTextSha256(evidence.displayTextSha256());
        po.setQualityJson("{\"score\":" + Double.toString(evidence.quality()) + "}");
        po.setStatus("ACTIVE");
        mapper.insertEvidenceUnit(po);
        EvidenceUnitPO persisted = mapper.selectEvidenceUnit(po.getId());
        if (persisted == null || !po.getVersionId().equals(persisted.getVersionId())
                || !po.getRevisionId().equals(persisted.getRevisionId())
                || !po.getPageId().equals(persisted.getPageId())
                || !Objects.equals(po.getSectionId(), persisted.getSectionId())
                || !po.getUnitType().equals(persisted.getUnitType())
                || !po.getModality().equals(persisted.getModality())
                || !po.getSourceChannel().equals(persisted.getSourceChannel())
                || !Objects.equals(po.getDisplayTextObjectKey(), persisted.getDisplayTextObjectKey())
                || !Objects.equals(po.getDisplayTextObjectVersionId(), persisted.getDisplayTextObjectVersionId())
                || !Objects.equals(po.getVisualObjectKey(), persisted.getVisualObjectKey())
                || !Objects.equals(po.getVisualObjectVersionId(), persisted.getVisualObjectVersionId())
                || !Objects.equals(po.getDisplayTextSha256(), persisted.getDisplayTextSha256())
                || !sameJson(po.getQualityJson(), persisted.getQualityJson())
                || !po.getStatus().equals(persisted.getStatus())) {
            throw new IllegalStateException("immutable evidence unit collided with different content");
        }
        evidence.regions().forEach(region -> persistEvidenceRegion(evidence.evidenceId(), region));
    }

    private void persistEvidenceRegion(String evidenceId, EvidenceRegion region) {
        EvidenceRegionPO po = new EvidenceRegionPO();
        po.setEvidenceId(evidenceId);
        po.setPageId(region.pageId());
        po.setOrdinal(region.ordinal());
        po.setBboxJson("{\"x1\":" + region.boundingBox().x1() + ",\"y1\":"
                + region.boundingBox().y1() + ",\"x2\":" + region.boundingBox().x2()
                + ",\"y2\":" + region.boundingBox().y2() + "}");
        po.setDisplayCharStart(region.displayCharStart());
        po.setDisplayCharEnd(region.displayCharEnd());
        po.setSourceBlockRef(region.sourceBlockRef());
        mapper.insertEvidenceRegion(po);
        EvidenceRegionPO persisted = mapper.selectEvidenceRegion(evidenceId, region.ordinal());
        if (persisted == null || !po.getPageId().equals(persisted.getPageId())
                || !sameJson(po.getBboxJson(), persisted.getBboxJson())
                || !Objects.equals(po.getDisplayCharStart(), persisted.getDisplayCharStart())
                || !Objects.equals(po.getDisplayCharEnd(), persisted.getDisplayCharEnd())
                || !Objects.equals(po.getSourceBlockRef(), persisted.getSourceBlockRef())) {
            throw new IllegalStateException("immutable evidence region collided with different content");
        }
    }

    private void persistEvidenceRelation(EvidenceRelation relation) {
        EvidenceRelationPO po = new EvidenceRelationPO();
        po.setFromEvidenceId(relation.fromEvidenceId());
        po.setToEvidenceId(relation.toEvidenceId());
        po.setRelationType(relation.relationType().name());
        po.setWeight(relation.weight());
        mapper.insertEvidenceRelation(po);
        EvidenceRelationPO persisted = mapper.selectEvidenceRelation(po.getFromEvidenceId(),
                po.getToEvidenceId(), po.getRelationType());
        if (persisted == null || Math.abs(po.getWeight() - persisted.getWeight()) > 0.000001) {
            throw new IllegalStateException("immutable evidence relation collided with different content");
        }
    }

    private void persistSection(String revisionId, DocumentSection section) {
        MaterialSectionPO po = new MaterialSectionPO();
        po.setId(section.sectionId());
        po.setRevisionId(revisionId);
        po.setParentSectionId(section.parentSectionId());
        po.setLevel(section.level());
        po.setOrdinal(section.ordinal());
        po.setPageStart(section.pageStart());
        po.setPageEnd(section.pageEnd());
        // Heading evidence is linked after Evidence Units are created; the structure artifact keeps the block ref.
        po.setHeadingEvidenceId(null);
        po.setStructureHash(section.structureHash());
        mapper.insertSection(po);
        MaterialSectionPO persisted = mapper.selectSection(revisionId, section.ordinal());
        if (persisted == null || !section.sectionId().equals(persisted.getId())
                || !Objects.equals(section.parentSectionId(), persisted.getParentSectionId())
                || section.level() != persisted.getLevel()
                || section.pageStart() != persisted.getPageStart()
                || section.pageEnd() != persisted.getPageEnd()
                || !section.structureHash().equals(persisted.getStructureHash())) {
            throw new IllegalStateException("immutable document section collided with different structure");
        }
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

    private static boolean sameJson(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        try {
            JsonNode expectedNode = JSON.readTree(expected);
            JsonNode actualNode = JSON.readTree(actual);
            return expectedNode.equals(actualNode);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("persisted evidence JSON is invalid", e);
        }
    }

    private static Set<Integer> excludedPages(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            JsonNode node = JSON.readTree(value);
            if (!node.isArray()) throw new IllegalStateException("excluded_pages_json must be an array");
            TreeSet<Integer> pages = new TreeSet<>();
            node.forEach(page -> pages.add(page.asInt()));
            return Set.copyOf(pages);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("excluded_pages_json is invalid", e);
        }
    }

}
