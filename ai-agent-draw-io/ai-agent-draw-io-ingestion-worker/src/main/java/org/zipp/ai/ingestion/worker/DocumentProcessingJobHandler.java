package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructureResult;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceBuildResult;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceSourcePage;
import org.zipp.ai.domain.ingestion.model.valobj.NativePageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageBatch;
import org.zipp.ai.domain.ingestion.model.valobj.RetrievalBuildResult;
import org.zipp.ai.domain.ingestion.model.valobj.RetrievalChunkArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionCanonicalPageWork;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropManifest;
import org.zipp.ai.domain.ingestion.model.valobj.VisualProcessingResult;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.projection.RetrievalParentContext;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile;
import org.zipp.ai.ingestion.worker.document.EvidenceBuildLimits;
import org.zipp.ai.ingestion.worker.document.ProcessingLimitExceededException;
import org.zipp.ai.ingestion.worker.document.VisualCropDeriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DocumentProcessingJobHandler {

    private static final long MAX_ORIGINAL_BYTES = 100L * 1024 * 1024;
    private static final long MAX_PAGE_ARTIFACT_BYTES = 20L * 1024 * 1024;
    private static final Duration HEARTBEAT_EXTENSION = Duration.ofMinutes(30);
    private static final String JSON_GZIP = "application/json+gzip";

    private final DocumentProcessingWorkPort work;
    private final RevisionArtifactPort artifacts;
    private final DocumentParserPort parser;
    private final OcrEnginePort ocr;
    private final OcrSelectionPolicy ocrSelection;
    private final CanonicalPageAssembler canonicalAssembler;
    private final DocumentStructureBuilder structureBuilder;
    private final VisualCandidateSelectionPolicy visualSelection;
    private final EvidenceUnitBuilder evidenceBuilder;
    private final EvidenceBuildLimits evidenceLimits;
    private final RetrievalChunkBuilder retrievalBuilder;
    private final VisualCropDeriver visualCropper;
    private final RevisionPageCodec codec;
    private final DocumentProcessingProfile profile;
    private final ProcessingQueuePort queue;
    private final Clock clock;

    public DocumentProcessingJobHandler(DocumentProcessingWorkPort work, RevisionArtifactPort artifacts,
                                        DocumentParserPort parser, OcrEnginePort ocr,
                                        OcrSelectionPolicy ocrSelection,
                                        CanonicalPageAssembler canonicalAssembler,
                                        DocumentStructureBuilder structureBuilder,
                                        VisualCandidateSelectionPolicy visualSelection,
                                        EvidenceUnitBuilder evidenceBuilder,
                                        EvidenceBuildLimits evidenceLimits,
                                        RetrievalChunkBuilder retrievalBuilder,
                                        VisualCropDeriver visualCropper, RevisionPageCodec codec,
                                        DocumentProcessingProfile profile,
                                        ProcessingQueuePort queue, Clock clock) {
        this.work = Objects.requireNonNull(work, "work");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ocr = Objects.requireNonNull(ocr, "ocr");
        this.ocrSelection = Objects.requireNonNull(ocrSelection, "ocrSelection");
        this.canonicalAssembler = Objects.requireNonNull(canonicalAssembler, "canonicalAssembler");
        this.structureBuilder = Objects.requireNonNull(structureBuilder, "structureBuilder");
        this.visualSelection = Objects.requireNonNull(visualSelection, "visualSelection");
        this.evidenceBuilder = Objects.requireNonNull(evidenceBuilder, "evidenceBuilder");
        this.evidenceLimits = Objects.requireNonNull(evidenceLimits, "evidenceLimits");
        this.retrievalBuilder = Objects.requireNonNull(retrievalBuilder, "retrievalBuilder");
        this.visualCropper = Objects.requireNonNull(visualCropper, "visualCropper");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobOutcome handle(ProcessingJobLease lease) {
        var job = Objects.requireNonNull(lease, "lease").job();
        String revisionId = job.target().revisionId();
        if (revisionId == null) {
            return JobOutcome.permanent("UNSUPPORTED_DOCUMENT_TARGET");
        }
        try {
            return switch (job.stage()) {
                case EXTRACT_NATIVE -> extract(revisionId, lease);
                case OCR_SELECTED_PAGES -> ocr(revisionId, lease);
                case NORMALIZE_CANONICAL_PAGES -> canonicalize(revisionId, lease);
                case BUILD_DOCUMENT_STRUCTURE -> buildStructure(revisionId, lease);
                case ANALYZE_VISUALS -> prepareVisualCrops(revisionId, lease);
                case BUILD_EVIDENCE_UNITS -> buildEvidence(revisionId, lease);
                case BUILD_RETRIEVAL_CHUNKS -> buildRetrieval(revisionId, lease);
                default -> JobOutcome.permanent("UNSUPPORTED_DOCUMENT_STAGE");
            };
        } catch (ProcessingLimitExceededException e) {
            return JobOutcome.permanent("DOCUMENT_PROCESSING_LIMIT_EXCEEDED");
        } catch (IllegalArgumentException e) {
            return JobOutcome.permanent("INVALID_DOCUMENT_CONTENT");
        } catch (RuntimeException e) {
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        }
    }

    private JobOutcome extract(String revisionId, ProcessingJobLease lease) {
        var source = work.findExtractionWork(revisionId, fence(lease)).orElse(null);
        if (source == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(source.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (!profile.extractionInput(source.original().contentSha256()).equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        Path temporaryDirectory = createTempDirectory();
        try {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            Path original = artifacts.download(source.original(), MAX_ORIGINAL_BYTES,
                    temporaryDirectory.resolve("original"));
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            var parsed = parser.parse(original, source.detectedMediaType(), temporaryDirectory.resolve("pages"));
            List<NativePageResult> results = new ArrayList<>();
            for (var page : parsed.pages()) {
                if (!heartbeat(lease)) {
                    return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
                }
                int pageNo = page.extraction().pageNo();
                String prefix = pagePrefix(revisionId, pageNo);
                StoredArtifact image = artifacts.putImmutable(prefix + "page.png",
                        readAllBytes(page.renderedImage()), "image/png");
                StoredArtifact nativeExtraction = artifacts.putImmutable(prefix + "native-extraction.json.gz",
                        codec.encode(page.extraction()), JSON_GZIP);
                boolean selected = ocrSelection.requiresOcr(source.detectedMediaType(),
                        page.extraction().nativeTextQuality(), page.extraction().rasterRegions());
                StoredArtifact rawExtraction = selected ? null : artifacts.putImmutable(
                        prefix + "raw-extraction.json.gz", codec.encode(page.extraction()), JSON_GZIP);
                results.add(new NativePageResult(pageNo, page.extraction().width(), page.extraction().height(),
                        selected, image, nativeExtraction, rawExtraction));
            }
            List<ProcessingJob> nextJobs = results.stream().map(page -> {
                ProcessingJobStage stage = page.ocrRequired()
                        ? ProcessingJobStage.OCR_SELECTED_PAGES : ProcessingJobStage.NORMALIZE_CANONICAL_PAGES;
                return nextJob(revisionId, stage, "page:" + page.pageNo(),
                        page.ocrRequired()
                                ? profile.ocrInput(page.pageImage().contentSha256(),
                                        page.nativeExtraction().contentSha256())
                                : profile.canonicalInput(page.rawExtraction().contentSha256()));
            }).toList();
            boolean committed = work.commitNativeExtraction(source, results, nextJobs, fence(lease));
            if (!committed) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            return JobOutcome.succeeded();
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    private JobOutcome ocr(String revisionId, ProcessingJobLease lease) {
        RevisionPageBatch batch = work.findPageBatch(revisionId, fence(lease)).orElse(null);
        if (batch == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(batch.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (batch.pages().size() != 1 || !batch.pages().get(0).requiresOcr()) {
            throw new IllegalArgumentException("OCR jobs must target exactly one selected page");
        }
        var selectedPage = batch.pages().get(0);
        if (!profile.ocrInput(selectedPage.pageImage().contentSha256(),
                selectedPage.nativeExtraction().contentSha256()).equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        Path temporaryDirectory = createTempDirectory();
        try {
            List<OcrPageResult> results = new ArrayList<>();
            for (var page : batch.pages()) {
                if (!page.requiresOcr()) {
                    continue;
                }
                if (!heartbeat(lease)) {
                    return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
                }
                Path pageImage = artifacts.download(page.pageImage(), MAX_PAGE_ARTIFACT_BYTES,
                        temporaryDirectory.resolve("page-" + page.pageNo() + ".png"));
                PageExtraction nativeExtraction = codec.decodeExtraction(
                        artifacts.read(page.nativeExtraction(), MAX_PAGE_ARTIFACT_BYTES));
                PageExtraction merged = nativeExtraction.withOcr(ocr.recognize(pageImage, page.pageNo()));
                StoredArtifact raw = artifacts.putImmutable(pagePrefix(revisionId, page.pageNo())
                        + "raw-extraction.json.gz", codec.encode(merged), JSON_GZIP);
                results.add(new OcrPageResult(page.pageNo(), merged.ocrResult().confidence(),
                        merged.ocrResult().confidence() < canonicalAssembler.lowConfidenceThreshold(), raw));
            }
            OcrPageResult result = results.get(0);
            if (!work.commitOcr(batch, results, nextJob(revisionId,
                    ProcessingJobStage.NORMALIZE_CANONICAL_PAGES, "page:" + result.pageNo(),
                    profile.canonicalInput(result.rawExtraction().contentSha256())),
                    fence(lease))) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            return JobOutcome.succeeded();
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    private JobOutcome canonicalize(String revisionId, ProcessingJobLease lease) {
        RevisionPageBatch batch = work.findPageBatch(revisionId, fence(lease)).orElse(null);
        if (batch == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(batch.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (batch.pages().size() != 1) {
            throw new IllegalArgumentException("canonical jobs must target exactly one page");
        }
        StoredArtifact expectedRawSource = batch.pages().get(0).rawExtraction() == null
                ? batch.pages().get(0).nativeExtraction() : batch.pages().get(0).rawExtraction();
        if (!profile.canonicalInput(expectedRawSource.contentSha256()).equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        List<CanonicalPageResult> results = new ArrayList<>();
        for (var page : batch.pages()) {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            StoredArtifact rawSource = page.rawExtraction() == null
                    ? page.nativeExtraction() : page.rawExtraction();
            PageExtraction extraction = codec.decodeExtraction(
                    artifacts.read(rawSource, MAX_PAGE_ARTIFACT_BYTES));
            StoredArtifact canonical = artifacts.putImmutable(pagePrefix(revisionId, page.pageNo())
                    + "canonical-page.json.gz", codec.encode(canonicalAssembler.assemble(extraction)), JSON_GZIP);
            results.add(new CanonicalPageResult(page.pageNo(), canonical));
        }
        if (!work.commitCanonical(batch, results,
                nextJob(revisionId, ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE, "root",
                        profile.structureSeed()), fence(lease))) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        return JobOutcome.succeeded();
    }

    private JobOutcome buildStructure(String revisionId, ProcessingJobLease lease) {
        var source = work.findStructureWork(revisionId, fence(lease)).orElse(null);
        if (source == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(source.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        List<String> canonicalHashes = source.pages().stream()
                .map(page -> page.canonicalPage().contentSha256()).toList();
        if (!profile.structureInput(canonicalHashes).equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        List<CanonicalPage> canonicalPages = new ArrayList<>();
        for (var page : source.pages()) {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            var canonical = codec.decodeCanonicalPage(
                    artifacts.read(page.canonicalPage(), MAX_PAGE_ARTIFACT_BYTES));
            if (canonical.pageNo() != page.pageNo()) {
                throw new IllegalArgumentException("canonical page number does not match its exact artifact pin");
            }
            canonicalPages.add(canonical);
        }
        var structure = structureBuilder.build(canonicalPages);
        StoredArtifact structureArtifact = artifacts.putImmutable(
                "revisions/" + revisionId + "/document-structure.json.gz",
                codec.encode(structure), JSON_GZIP);
        DocumentStructureResult result = new DocumentStructureResult(structure, structureArtifact);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.ANALYZE_VISUALS, "root",
                profile.visualInput(structure.structureHash(), structureArtifact.contentSha256()));
        if (!work.commitStructure(source, result, successor, fence(lease))) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        return JobOutcome.succeeded();
    }

    private JobOutcome prepareVisualCrops(String revisionId, ProcessingJobLease lease) {
        var source = work.findVisualWork(revisionId, fence(lease)).orElse(null);
        if (source == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(source.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (!heartbeat(lease)) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        var structure = codec.decodeDocumentStructure(
                artifacts.read(source.structureArtifact(), MAX_PAGE_ARTIFACT_BYTES));
        if (!profile.visualInput(structure.structureHash(), source.structureArtifact().contentSha256())
                .equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        int pageCount = source.pages().stream().mapToInt(page -> page.pageNo()).max().orElseThrow();
        var selection = visualSelection.select(structure, pageCount);
        Map<Integer, RevisionCanonicalPageWork> pages = new HashMap<>();
        source.pages().forEach(page -> pages.put(page.pageNo(), page));
        List<VisualCropArtifact> crops = new ArrayList<>();
        int loadedPageNo = -1;
        byte[] loadedPageImage = null;
        for (var candidate : selection.selectedCandidates()) {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            var page = pages.get(candidate.pageNo());
            if (page == null) {
                throw new IllegalArgumentException("visual candidate does not reference a pinned page");
            }
            if (loadedPageNo != candidate.pageNo()) {
                loadedPageNo = candidate.pageNo();
                loadedPageImage = artifacts.read(page.pageImage(), MAX_PAGE_ARTIFACT_BYTES);
            }
            StoredArtifact crop = artifacts.putImmutable("revisions/" + revisionId + "/visual-candidates/"
                    + candidate.candidateId() + ".png", visualCropper.derive(loadedPageImage, candidate), "image/png");
            crops.add(new VisualCropArtifact(page.pageId(), candidate, crop));
        }
        var manifest = new VisualCropManifest(
                "visual-crop-manifest-v1", structure.structureHash(), visualSelection.fingerprint(),
                selection.totalCandidateCount(), selection.skippedCandidateCount(), crops);
        StoredArtifact manifestArtifact = artifacts.putImmutable(
                "revisions/" + revisionId + "/visual-crop-manifest.json.gz",
                codec.encode(manifest), JSON_GZIP);
        var result = new VisualProcessingResult(manifest, manifestArtifact);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.BUILD_EVIDENCE_UNITS, "root",
                profile.evidenceInput(manifestArtifact.contentSha256()));
        if (!work.commitVisualCrops(source, result, successor, fence(lease))) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        return JobOutcome.succeeded();
    }

    private JobOutcome buildEvidence(String revisionId, ProcessingJobLease lease) {
        var source = work.findEvidenceWork(revisionId, fence(lease)).orElse(null);
        if (source == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(source.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (!profile.evidenceInput(source.visualManifestArtifact().contentSha256())
                .equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        var structure = codec.decodeDocumentStructure(
                artifacts.read(source.structureArtifact(), MAX_PAGE_ARTIFACT_BYTES),
                evidenceLimits.maximumArtifactUncompressedBytes());
        var visualManifest = codec.decodeVisualCropManifest(
                artifacts.read(source.visualManifestArtifact(), MAX_PAGE_ARTIFACT_BYTES),
                evidenceLimits.maximumArtifactUncompressedBytes());
        List<EvidenceSourcePage> pages = new ArrayList<>();
        long cumulativeCharacters = 0;
        long cumulativeRegions = 0;
        for (var page : source.pages()) {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            CanonicalPage canonical = codec.decodeCanonicalPage(
                    artifacts.read(page.canonicalPage(), MAX_PAGE_ARTIFACT_BYTES),
                    evidenceLimits.maximumArtifactUncompressedBytes());
            if (canonical.pageNo() != page.pageNo()) {
                throw new IllegalArgumentException("canonical page number does not match its evidence pin");
            }
            long pageCharacters = canonical.blocks().stream().mapToLong(block ->
                    (long) block.extractedText().length() + block.displayText().length()).sum();
            long pageRegions = canonical.rasterRegions().size()
                    + canonical.blocks().stream().mapToLong(block -> block.regions().size()
                            + block.sourceMap().stream().mapToLong(span -> span.regions().size()).sum()).sum();
            if (pageCharacters > evidenceLimits.maximumDocumentCharacters() - cumulativeCharacters
                    || pageRegions > evidenceLimits.maximumDocumentRegions() - cumulativeRegions) {
                throw new ProcessingLimitExceededException(
                        "document exceeds the cumulative evidence build budget");
            }
            cumulativeCharacters += pageCharacters;
            cumulativeRegions += pageRegions;
            pages.add(new EvidenceSourcePage(page.pageId(), canonical, page.canonicalPage()));
        }
        var manifest = evidenceBuilder.build(source.revisionId(), source.versionId(),
                structure, pages, visualManifest);
        StoredArtifact manifestArtifact = artifacts.putImmutable(
                "revisions/" + revisionId + "/evidence-manifest.json.gz", codec.encode(manifest), JSON_GZIP);
        EvidenceBuildResult result = new EvidenceBuildResult(manifest, manifestArtifact);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS, "root",
                profile.retrievalInput(manifestArtifact.contentSha256()));
        if (!work.commitEvidence(source, result, successor, fence(lease))) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        return JobOutcome.succeeded();
    }

    private JobOutcome buildRetrieval(String revisionId, ProcessingJobLease lease) {
        var source = work.findRetrievalWork(revisionId, fence(lease)).orElse(null);
        if (source == null) {
            return JobOutcome.succeeded();
        }
        if (!profile.overallFingerprint().equals(source.processingFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        if (!profile.retrievalInput(source.evidenceManifestArtifact().contentSha256())
                .equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        var evidence = codec.decodeEvidenceManifest(
                artifacts.read(source.evidenceManifestArtifact(), MAX_PAGE_ARTIFACT_BYTES),
                evidenceLimits.maximumArtifactUncompressedBytes());
        if (!source.revisionId().equals(evidence.revisionId())
                || !source.versionId().equals(evidence.versionId())) {
            throw new IllegalArgumentException("Evidence manifest does not belong to its exact retrieval pin");
        }
        var manifest = retrievalBuilder.build(evidence);
        if (!profile.retrieval().equals(manifest.builderFingerprint())) {
            return JobOutcome.transientFailure("PROCESSING_PROFILE_UNAVAILABLE");
        }
        List<RetrievalChunkArtifact> chunkArtifacts = new ArrayList<>();
        for (var chunk : manifest.chunks()) {
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            String prefix = "revisions/" + revisionId + "/retrieval/" + chunk.chunkId();
            StoredArtifact retrievalText = artifacts.putImmutable(prefix + ".json.gz",
                    codec.encode(chunk), JSON_GZIP);
            StoredArtifact parent = chunk.parentContext() == null ? null : artifacts.putImmutable(
                    prefix + ".parent.json.gz", codec.encode(new RetrievalParentContext(
                            chunk.chunkId(), chunk.parentContext(), chunk.parentEvidenceIds())), JSON_GZIP);
            chunkArtifacts.add(new RetrievalChunkArtifact(chunk.chunkId(), retrievalText, parent));
        }
        StoredArtifact manifestArtifact = artifacts.putImmutable(
                "revisions/" + revisionId + "/retrieval-manifest.json.gz",
                codec.encode(manifest), JSON_GZIP);
        RetrievalBuildResult result = new RetrievalBuildResult(manifest, manifestArtifact, chunkArtifacts);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.BUILD_LEXICAL_PROJECTION, "root",
                profile.lexicalProjectionInput(manifestArtifact.contentSha256()));
        if (!work.commitRetrieval(source, result, successor, fence(lease))) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        return JobOutcome.succeeded();
    }

    private boolean heartbeat(ProcessingJobLease lease) {
        var job = lease.job();
        return queue.heartbeat(job.id(), job.leaseOwner(), lease.fenceToken(),
                clock.instant(), HEARTBEAT_EXTENSION);
    }

    private ProcessingJob nextJob(String revisionId, ProcessingJobStage stage,
                                  String workKey, String inputFingerprint) {
        return ProcessingJob.enqueue("job_" + UUID.randomUUID(), ProcessingJobTarget.forRevision(revisionId), stage,
                workKey, inputFingerprint, 0, clock.instant());
    }

    private static String pagePrefix(String revisionId, int pageNo) {
        return "revisions/" + revisionId + "/pages/" + pageNo + "/";
    }

    private static WorkerFence fence(ProcessingJobLease lease) {
        return new WorkerFence(lease.job().id(), lease.job().leaseOwner(), lease.fenceToken());
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("revision-processing-");
        } catch (IOException e) {
            throw new IllegalStateException("worker temp directory is unavailable", e);
        }
    }

    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("page image could not be read", e);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Worker temp storage is ephemeral and never authoritative.
                }
            });
        } catch (IOException ignored) {
            // Worker temp storage is ephemeral and never authoritative.
        }
    }

}
