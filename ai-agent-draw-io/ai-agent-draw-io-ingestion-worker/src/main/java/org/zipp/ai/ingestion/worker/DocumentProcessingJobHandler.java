package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.NativePageResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrPageResult;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionPageBatch;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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
    private final RevisionPageCodec codec;
    private final DocumentProcessingProfile profile;
    private final ProcessingQueuePort queue;
    private final Clock clock;

    public DocumentProcessingJobHandler(DocumentProcessingWorkPort work, RevisionArtifactPort artifacts,
                                        DocumentParserPort parser, OcrEnginePort ocr,
                                        OcrSelectionPolicy ocrSelection,
                                        CanonicalPageAssembler canonicalAssembler,
                                        RevisionPageCodec codec, DocumentProcessingProfile profile,
                                        ProcessingQueuePort queue, Clock clock) {
        this.work = Objects.requireNonNull(work, "work");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ocr = Objects.requireNonNull(ocr, "ocr");
        this.ocrSelection = Objects.requireNonNull(ocrSelection, "ocrSelection");
        this.canonicalAssembler = Objects.requireNonNull(canonicalAssembler, "canonicalAssembler");
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
                default -> JobOutcome.permanent("UNSUPPORTED_DOCUMENT_STAGE");
            };
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
                        sha256(profile.overallFingerprint() + ":structure-v1")), fence(lease))) {
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
