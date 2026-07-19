package org.zipp.ai.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocumentProcessingJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile PROFILE =
            org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile.of(200, "tesseract",
                    "eng+chi_sim", 120, "test-tesseract-4.1.1",
                    new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                    new CanonicalPageAssembler(0.70), new DocumentStructureBuilder());

    @Test
    void processesNativeOcrAndCanonicalStagesWithExactArtifacts() throws Exception {
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        StoredArtifact original = artifacts.putImmutable("original/blob", new byte[]{1}, "application/pdf");
        InMemoryWork work = new InMemoryWork(new RevisionExtractionWork(
                "rev_1", "application/pdf", 0, 0, PROFILE.overallFingerprint(), original));
        RecordingQueue queue = new RecordingQueue();
        var parser = (org.zipp.ai.domain.ingestion.port.DocumentParserPort) (path, mediaType, directory) -> {
            try {
                Files.createDirectories(directory);
                Path image = directory.resolve("page-1.png");
                Files.write(image, new byte[]{2, 3});
                PageExtraction extraction = new PageExtraction(1, 100, 200, List.of(),
                        NativeTextQuality.empty(), null);
                return new ParsedDocument(List.of(new ParsedPage(extraction, image)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        var ocr = (org.zipp.ai.domain.ingestion.port.OcrEnginePort) (path, pageNo) ->
                new OcrResult(pageNo, "Agile flow", 0.91, List.of(
                        new OcrWord("Agile", new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.2), 0.92, "line:1"),
                        new OcrWord("flow", new NormalizedBoundingBox(0.21, 0.1, 0.3, 0.2), 0.90, "line:1")));
        DocumentProcessingJobHandler handler = new DocumentProcessingJobHandler(work, artifacts, parser, ocr,
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01), new CanonicalPageAssembler(0.70),
                new DocumentStructureBuilder(), new RevisionPageCodec(new ObjectMapper()), PROFILE,
                queue, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE,
                PROFILE.extractionInput(original.contentSha256()))).kind());
        assertEquals(ProcessingJobStage.OCR_SELECTED_PAGES, work.nextStage);
        assertEquals("page:1", work.nextWorkKey);
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.OCR_SELECTED_PAGES,
                        PROFILE.ocrInput(work.batch.pages().get(0).pageImage().contentSha256(),
                                work.batch.pages().get(0).nativeExtraction().contentSha256()))).kind());
        assertEquals(ProcessingJobStage.NORMALIZE_CANONICAL_PAGES, work.nextStage);
        assertEquals("page:1", work.nextWorkKey);
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.NORMALIZE_CANONICAL_PAGES,
                        PROFILE.canonicalInput(work.batch.pages().get(0).rawExtraction().contentSha256()))).kind());

        assertEquals(ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE, work.nextStage);
        assertEquals("root", work.nextWorkKey);
        assertNotNull(work.canonicalArtifact);
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE,
                        PROFILE.structureInput(List.of(work.canonicalArtifact.contentSha256())))).kind());
        assertEquals(ProcessingJobStage.ANALYZE_VISUALS, work.nextStage);
        assertNotNull(work.structureResult);
        assertEquals("revisions/rev_1/document-structure.json.gz",
                work.structureResult.artifact().objectKey());
        assertEquals(1, work.structureResult.structure().sections().size());
        assertEquals(6, queue.heartbeats);
        assertEquals(JobOutcome.Kind.PERMANENT_FAILURE,
                handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE, "0".repeat(64))).kind());
    }

    @Test
    void extractionWaitsForAWorkerMatchingThePersistedRevisionProfile() {
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        StoredArtifact original = artifacts.putImmutable("original/blob", new byte[]{1}, "application/pdf");
        InMemoryWork work = new InMemoryWork(new RevisionExtractionWork(
                "rev_1", "application/pdf", 0, 0, "e".repeat(64), original));
        DocumentProcessingJobHandler handler = new DocumentProcessingJobHandler(work, artifacts,
                (path, mediaType, directory) -> { throw new AssertionError("mismatched parser must not run"); },
                (path, pageNo) -> { throw new AssertionError("mismatched OCR must not run"); },
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01), new CanonicalPageAssembler(0.70),
                new DocumentStructureBuilder(), new RevisionPageCodec(new ObjectMapper()), PROFILE,
                new RecordingQueue(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE,
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.extractionInput(
                        original.contentSha256(), "e".repeat(64))));

        assertEquals(JobOutcome.Kind.TRANSIENT_FAILURE, outcome.kind());
        assertEquals("PROCESSING_PROFILE_UNAVAILABLE", outcome.errorCode());
    }

    private static ProcessingJobLease lease(ProcessingJobStage stage, String inputFingerprint) {
        ProcessingJob job = ProcessingJob.enqueue("job_" + stage,
                ProcessingJobTarget.forRevision("rev_1"), stage, "root", inputFingerprint, 0, NOW);
        long fence = job.claim("worker-1", NOW, Duration.ofMinutes(5));
        return new ProcessingJobLease(job, fence);
    }

    private static final class InMemoryWork implements DocumentProcessingWorkPort {
        private final RevisionExtractionWork extraction;
        private RevisionPageBatch batch;
        private ProcessingJobStage nextStage;
        private String nextWorkKey;
        private StoredArtifact canonicalArtifact;
        private RevisionStructureWork structureWork;
        private DocumentStructureResult structureResult;

        private InMemoryWork(RevisionExtractionWork extraction) {
            this.extraction = extraction;
        }

        @Override
        public Optional<RevisionExtractionWork> findExtractionWork(String revisionId, WorkerFence fence) {
            return Optional.of(extraction);
        }

        @Override
        public boolean commitNativeExtraction(RevisionExtractionWork work, List<NativePageResult> pages,
                                              List<ProcessingJob> nextJobs, WorkerFence fence) {
            NativePageResult page = pages.get(0);
            batch = new RevisionPageBatch(work.revisionId(), 1, 0, work.processingFingerprint(),
                    List.of(new RevisionPageWork(page.pageNo(), page.width(), page.height(),
                            OcrPageStatus.REQUIRED, page.pageImage(), page.nativeExtraction(), null)));
            nextStage = nextJobs.get(0).stage();
            nextWorkKey = nextJobs.get(0).workKey();
            return true;
        }

        @Override
        public Optional<RevisionPageBatch> findPageBatch(String revisionId, WorkerFence fence) {
            return Optional.of(batch);
        }

        @Override
        public boolean commitOcr(RevisionPageBatch batch, List<OcrPageResult> pages,
                                 ProcessingJob nextJob, WorkerFence fence) {
            RevisionPageWork current = batch.pages().get(0);
            this.batch = new RevisionPageBatch(batch.revisionId(), 2, batch.materialLifecycleGeneration(),
                    batch.processingFingerprint(),
                    List.of(new RevisionPageWork(current.pageNo(), current.width(), current.height(),
                            OcrPageStatus.COMPLETED, current.pageImage(), current.nativeExtraction(),
                            pages.get(0).rawExtraction())));
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override
        public boolean commitCanonical(RevisionPageBatch batch, List<CanonicalPageResult> pages,
                                       ProcessingJob nextJob, WorkerFence fence) {
            canonicalArtifact = pages.get(0).canonicalPage();
            RevisionPageWork page = batch.pages().get(0);
            structureWork = new RevisionStructureWork(batch.revisionId(), "ver_1", 3,
                    batch.materialLifecycleGeneration(), batch.processingFingerprint(),
                    List.of(new RevisionCanonicalPageWork("page_1", page.pageNo(),
                            page.pageImage(), canonicalArtifact)));
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override
        public Optional<RevisionStructureWork> findStructureWork(String revisionId, WorkerFence fence) {
            return Optional.ofNullable(structureWork);
        }

        @Override
        public boolean commitStructure(RevisionStructureWork work, DocumentStructureResult result,
                                       ProcessingJob nextJob, WorkerFence fence) {
            structureResult = result;
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }
    }

    private static final class InMemoryArtifacts implements RevisionArtifactPort {
        private final Map<String, byte[]> bytes = new HashMap<>();
        private final Map<String, StoredArtifact> artifacts = new HashMap<>();

        @Override
        public StoredArtifact putImmutable(String objectKey, byte[] content, String contentType) {
            StoredArtifact existing = artifacts.get(objectKey);
            if (existing != null) {
                return existing;
            }
            try {
                String hash = java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
                StoredArtifact artifact = new StoredArtifact(objectKey, "v1", hash, content.length, contentType);
                bytes.put(objectKey, content.clone());
                artifacts.put(objectKey, artifact);
                return artifact;
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] read(StoredArtifact artifact, long maximumBytes) {
            return bytes.get(artifact.objectKey()).clone();
        }

        @Override
        public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
            try {
                Files.write(destination, read(artifact, maximumBytes));
                return destination;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

    }

    private static final class RecordingQueue implements ProcessingQueuePort {
        private int heartbeats;

        @Override public void enqueue(ProcessingJob job) { }
        @Override public Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration leaseDuration,
                                                           Set<ProcessingJobStage> acceptedStages) {
            return Optional.empty();
        }
        @Override public boolean heartbeat(String jobId, String workerId, long fenceToken,
                                           Instant now, Duration extension) {
            heartbeats++;
            return true;
        }
        @Override public boolean succeed(String jobId, String workerId, long fenceToken) { return true; }
        @Override public boolean retry(String jobId, String workerId, long fenceToken,
                                       String errorCode, Instant retryAt) { return true; }
        @Override public boolean fail(String jobId, String workerId, long fenceToken,
                                      String errorCode) { return true; }
        @Override public int requeueExpiredLeases(Instant now, int limit) { return 0; }
    }
}
