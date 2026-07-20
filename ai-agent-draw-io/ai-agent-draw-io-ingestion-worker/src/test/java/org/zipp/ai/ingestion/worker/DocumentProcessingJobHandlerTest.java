package org.zipp.ai.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.projection.RetrievalTokenCounter;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.document.EvidenceBuildLimits;
import org.zipp.ai.ingestion.worker.document.VisualCropDeriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessingJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final EvidenceBuildLimits EVIDENCE_LIMITS =
            new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000);
    private static final RetrievalTokenCounter TOKEN_COUNTER = new RetrievalTokenCounter() {
        @Override public int count(String text) { return Math.max(1, text.codePointCount(0, text.length())); }
        @Override public String fingerprint() { return "retrieval-test-tokenizer-v1"; }
    };
    private static final RetrievalChunkBuilder RETRIEVAL_BUILDER = new RetrievalChunkBuilder(TOKEN_COUNTER);
    private static final org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile PROFILE =
            org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile.of(200, "tesseract",
                    "eng+chi_sim", 120, "test-tesseract-4.1.1",
                    new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                    new CanonicalPageAssembler(0.70), new DocumentStructureBuilder(),
                    new VisualCandidateSelectionPolicy(12, 0.15, 3),
                    new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                    new EvidenceUnitBuilder(), EVIDENCE_LIMITS, RETRIEVAL_BUILDER.fingerprint());

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
                new DocumentStructureBuilder(), new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new EvidenceUnitBuilder(), EVIDENCE_LIMITS, RETRIEVAL_BUILDER,
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new RevisionPageCodec(new ObjectMapper()), PROFILE,
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
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.ANALYZE_VISUALS,
                        PROFILE.visualInput(work.structureResult.structure().structureHash(),
                                work.structureResult.artifact().contentSha256()))).kind());
        assertEquals(ProcessingJobStage.BUILD_EVIDENCE_UNITS, work.nextStage);
        assertNotNull(work.visualResult);
        assertEquals(0, work.visualResult.manifest().totalCandidateCount());
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.BUILD_EVIDENCE_UNITS,
                        PROFILE.evidenceInput(work.visualResult.manifestArtifact().contentSha256()))).kind());
        assertEquals(ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS, work.nextStage);
        assertNotNull(work.evidenceResult);
        assertEquals("revisions/rev_1/evidence-manifest.json.gz",
                work.evidenceResult.manifestArtifact().objectKey());
        assertEquals(JobOutcome.Kind.SUCCEEDED,
                handler.handle(lease(ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS,
                        PROFILE.retrievalInput(work.evidenceResult.manifestArtifact().contentSha256()))).kind());
        assertEquals(ProcessingJobStage.BUILD_LEXICAL_PROJECTION, work.nextStage);
        assertNotNull(work.retrievalResult);
        assertEquals("revisions/rev_1/retrieval-manifest.json.gz",
                work.retrievalResult.manifestArtifact().objectKey());
        assertTrue(queue.heartbeats > 9);
        assertEquals(JobOutcome.Kind.PERMANENT_FAILURE,
                handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE, "0".repeat(64))).kind());
    }

    @Test
    void extractionSkipsExcludedPagesBeforeWritingDerivatives() {
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        StoredArtifact original = artifacts.putImmutable("original/blob", new byte[]{1}, "application/pdf");
        InMemoryWork work = new InMemoryWork(new RevisionExtractionWork(
                "rev_1", "application/pdf", 0, 0, PROFILE.overallFingerprint(), Set.of(1), original));
        var parser = (org.zipp.ai.domain.ingestion.port.DocumentParserPort) (path, mediaType, directory) -> {
            try {
                Files.createDirectories(directory);
                List<ParsedPage> pages = new ArrayList<>();
                for (int pageNo = 1; pageNo <= 2; pageNo++) {
                    Path image = directory.resolve("page-" + pageNo + ".png");
                    Files.write(image, new byte[]{(byte) pageNo});
                    pages.add(new ParsedPage(new PageExtraction(pageNo, 100, 200, List.of(),
                            NativeTextQuality.empty(), null), image));
                }
                return new ParsedDocument(pages);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        DocumentProcessingJobHandler handler = new DocumentProcessingJobHandler(work, artifacts, parser,
                (path, pageNo) -> { throw new AssertionError("OCR must not run during extraction"); },
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01), new CanonicalPageAssembler(0.70),
                new DocumentStructureBuilder(), new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new EvidenceUnitBuilder(), EVIDENCE_LIMITS, RETRIEVAL_BUILDER,
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new RevisionPageCodec(new ObjectMapper()), PROFILE, new RecordingQueue(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE,
                PROFILE.extractionInput(original.contentSha256())));

        assertEquals(JobOutcome.Kind.SUCCEEDED, outcome.kind());
        assertEquals(2, work.batch.pages().get(0).pageNo());
        assertTrue(artifacts.artifacts.keySet().stream().noneMatch(key -> key.contains("pages/1/")));
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
                new DocumentStructureBuilder(), new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new EvidenceUnitBuilder(), EVIDENCE_LIMITS, RETRIEVAL_BUILDER,
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new RevisionPageCodec(new ObjectMapper()), PROFILE,
                new RecordingQueue(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease(ProcessingJobStage.EXTRACT_NATIVE,
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.extractionInput(
                        original.contentSha256(), "e".repeat(64))));

        assertEquals(JobOutcome.Kind.TRANSIENT_FAILURE, outcome.kind());
        assertEquals("PROCESSING_PROFILE_UNAVAILABLE", outcome.errorCode());
    }

    @Test
    void evidenceBuildRejectsDocumentsAboveTheCumulativeCharacterBudget() {
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        StoredArtifact original = artifacts.putImmutable("original/blob", new byte[]{1}, "application/pdf");
        InMemoryWork work = new InMemoryWork(new RevisionExtractionWork(
                "rev_1", "application/pdf", 0, 0, PROFILE.overallFingerprint(), original));
        RevisionPageCodec codec = new RevisionPageCodec(new ObjectMapper());
        List<RevisionCanonicalPageWork> pageWork = new ArrayList<>();
        for (int pageNo = 1; pageNo <= 2; pageNo++) {
            String text = "a".repeat(1_300_000);
            NormalizedBoundingBox region = new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.2);
            CanonicalBlock block = new CanonicalBlock("block_" + pageNo, TextBlockKind.PARAGRAPH, 1,
                    List.of(region), TextSource.NATIVE, text, text,
                    List.of(new SourceMapSpan(0, text.length(), 0, text.length(), List.of(region))),
                    0.95, BoilerplatePosition.NONE);
            CanonicalPage canonical = new CanonicalPage(pageNo, 100, 100, List.of(block),
                    NativeTextQuality.empty(), null, false);
            StoredArtifact canonicalArtifact = artifacts.putImmutable("canonical-" + pageNo + ".json.gz",
                    codec.encode(canonical), "application/json+gzip");
            StoredArtifact image = artifacts.putImmutable("page-" + pageNo + ".png",
                    new byte[]{1}, "image/png");
            pageWork.add(new RevisionCanonicalPageWork("page_" + pageNo, pageNo, image, canonicalArtifact));
        }
        DocumentSection root = new DocumentSection("sec_root", null, 1, 1, 1, 2,
                null, "c".repeat(64));
        DocumentStructure structure = new DocumentStructure("document-structure-v1", List.of(root),
                List.of(), List.of(), "e".repeat(64));
        StoredArtifact structureArtifact = artifacts.putImmutable("structure.json.gz",
                codec.encode(structure), "application/json+gzip");
        VisualCropManifest visual = new VisualCropManifest("visual-crop-manifest-v1",
                structure.structureHash(), "selection-v1", 0, 0, List.of());
        StoredArtifact visualArtifact = artifacts.putImmutable("visual.json.gz",
                codec.encode(visual), "application/json+gzip");
        work.evidenceWork = new RevisionEvidenceWork("rev_1", "ver_1", 5, 0,
                PROFILE.overallFingerprint(), structureArtifact, visualArtifact, pageWork);
        DocumentProcessingJobHandler handler = new DocumentProcessingJobHandler(work, artifacts,
                (path, mediaType, directory) -> { throw new AssertionError("parser must not run"); },
                (path, pageNo) -> { throw new AssertionError("OCR must not run"); },
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01), new CanonicalPageAssembler(0.70),
                new DocumentStructureBuilder(), new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new EvidenceUnitBuilder(), EVIDENCE_LIMITS, RETRIEVAL_BUILDER,
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                codec, PROFILE, new RecordingQueue(), Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease(ProcessingJobStage.BUILD_EVIDENCE_UNITS,
                PROFILE.evidenceInput(visualArtifact.contentSha256())));

        assertEquals(JobOutcome.Kind.PERMANENT_FAILURE, outcome.kind());
        assertEquals("DOCUMENT_PROCESSING_LIMIT_EXCEEDED", outcome.errorCode());
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
        private RevisionVisualWork visualWork;
        private VisualProcessingResult visualResult;
        private RevisionEvidenceWork evidenceWork;
        private EvidenceBuildResult evidenceResult;
        private RevisionRetrievalWork retrievalWork;
        private RetrievalBuildResult retrievalResult;

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
            visualWork = new RevisionVisualWork(work.revisionId(), work.versionId(), 4,
                    work.materialLifecycleGeneration(), work.processingFingerprint(), result.artifact(), work.pages());
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override
        public Optional<RevisionVisualWork> findVisualWork(String revisionId, WorkerFence fence) {
            return Optional.ofNullable(visualWork);
        }

        @Override
        public boolean commitVisualCrops(RevisionVisualWork work, VisualProcessingResult result,
                                         ProcessingJob nextJob, WorkerFence fence) {
            visualResult = result;
            evidenceWork = new RevisionEvidenceWork(work.revisionId(), work.versionId(), 5,
                    work.materialLifecycleGeneration(), work.processingFingerprint(), work.structureArtifact(),
                    result.manifestArtifact(), work.pages());
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override
        public Optional<RevisionEvidenceWork> findEvidenceWork(String revisionId, WorkerFence fence) {
            return Optional.ofNullable(evidenceWork);
        }

        @Override
        public boolean commitEvidence(RevisionEvidenceWork work, EvidenceBuildResult result,
                                      ProcessingJob nextJob, WorkerFence fence) {
            evidenceResult = result;
            retrievalWork = new RevisionRetrievalWork(work.revisionId(), work.versionId(), "material_1",
                    OwnerType.USER, "user_1", 6, work.materialLifecycleGeneration(), work.processingFingerprint(),
                    result.manifestArtifact());
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override
        public Optional<RevisionRetrievalWork> findRetrievalWork(String revisionId, WorkerFence fence) {
            return Optional.ofNullable(retrievalWork);
        }

        @Override
        public boolean commitRetrieval(RevisionRetrievalWork work, RetrievalBuildResult result,
                                       ProcessingJob nextJob, WorkerFence fence) {
            retrievalResult = result;
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
