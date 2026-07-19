package org.zipp.ai.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;
import org.zipp.ai.domain.retrieval.port.VectorProjectionWorkPort;
import org.zipp.ai.domain.retrieval.projection.*;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.fake.FakeEmbeddingPort;
import org.zipp.ai.ingestion.worker.fake.FakeRetrievalVectorIndex;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorProjectionJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void coordinatesEmbedsUpsertsAndManifestsOneGenerationScopedBatch() {
        RevisionPageCodec codec = new RevisionPageCodec(new ObjectMapper());
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        RetrievalProjectionManifest retrieval = retrievalManifest();
        StoredArtifact retrievalArtifact = artifacts.putImmutable("retrieval-manifest.json.gz",
                codec.encode(retrieval), "application/json+gzip");
        RevisionProjectionContext context = new RevisionProjectionContext(
                "rev_1", "ver_1", "material_1", OwnerType.USER, "user_1", 7, 2,
                "d".repeat(64), retrievalArtifact);
        InMemoryWork work = new InMemoryWork(context);
        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        RecordingQueue queue = new RecordingQueue();
        VectorGenerationProfile profile = new VectorGenerationProfile(
                "drawio-test", "test", "multilingual-e5-large", "e".repeat(64),
                4, "cosine", "vector-v1", "tokenizer-v1");
        CountingEmbeddingPort embedding = new CountingEmbeddingPort(4);
        InMemoryEmbeddingCache embeddingCache = new InMemoryEmbeddingCache();
        VectorProjectionJobHandler handler = new VectorProjectionJobHandler(
                work, artifacts, embedding, embeddingCache, index,
                (ownerType, ownerKey) -> "tenant-opaque", new VectorProjectionPlanner(96, 1_000_000),
                codec, profile, queue, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(
                ProcessingJobStage.BUILD_LEXICAL_PROJECTION, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.lexicalProjectionInput(
                        retrievalArtifact.contentSha256(), context.processingFingerprint()))).kind());
        assertEquals(ProcessingJobStage.EMBED_CHUNK_BATCHES, work.nextStage);
        assertTrue(work.nextWorkKey.startsWith("ig:ig_"));

        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(
                ProcessingJobStage.EMBED_CHUNK_BATCHES, work.nextWorkKey,
                work.embeddingWork.batchInputFingerprint())).kind());
        assertEquals(ProcessingJobStage.UPSERT_VECTOR_BATCHES, work.nextStage);
        assertNotNull(work.embeddingResult);
        assertEquals(1, embedding.calls);

        // A retry reads the owner-scoped immutable vector cache and does not call inference again.
        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(
                ProcessingJobStage.EMBED_CHUNK_BATCHES, work.embeddingWork.workKey(),
                work.embeddingWork.batchInputFingerprint())).kind());
        assertEquals(1, embedding.calls);

        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(
                ProcessingJobStage.UPSERT_VECTOR_BATCHES, work.nextWorkKey,
                work.upsertWork.upsertInputFingerprint())).kind());
        assertEquals(ProcessingJobStage.VERIFY_PROJECTION_MANIFEST, work.nextStage);
        assertTrue(index.serializedRecords().contains("tenant_key=tenant-opaque"));
        assertFalse(index.serializedRecords().contains("user_1"));
        assertFalse(index.serializedRecords().contains("Agile development"));

        assertEquals(JobOutcome.Kind.SUCCEEDED, handler.handle(lease(
                ProcessingJobStage.VERIFY_PROJECTION_MANIFEST, work.nextWorkKey,
                work.manifestWork.verificationInputFingerprint())).kind());
        assertEquals(ProcessingJobStage.PUBLISH_REVISION, work.nextStage);
        assertEquals("revisions/rev_1/projections/" + profile.generationId()
                + "/projection-manifest.json.gz", work.manifestResult.artifact().objectKey());
        assertEquals(1, work.manifestResult.manifest().entries().size());
    }

    private ProcessingJobLease lease(ProcessingJobStage stage, String workKey, String fingerprint) {
        ProcessingJob job = ProcessingJob.enqueue("job_" + stage,
                ProcessingJobTarget.forRevision("rev_1"), stage, workKey, fingerprint, 0, NOW);
        long fence = job.claim("worker-1", NOW, Duration.ofMinutes(5));
        return new ProcessingJobLease(job, fence);
    }

    private RetrievalProjectionManifest retrievalManifest() {
        String text = "Agile development uses short feedback cycles.";
        RetrievalChunkProjection chunk = new RetrievalChunkProjection(
                "chunk_1", "page_1", "section_1", RetrievalChunkType.CONTENT,
                org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.TEXT, "en", true,
                RetrievalIndexMode.DENSE_AND_LEXICAL,
                text, "1".repeat(64), null, List.of(), 9, 0.95, 1,
                List.of(new RetrievalEvidenceMapping("evidence_1", ChunkEvidenceRole.PRIMARY,
                        0, null, null)));
        return new RetrievalProjectionManifest("retrieval-projection-v1", "rev_1", "ver_1",
                "2".repeat(64), "builder-v1", List.of(chunk),
                List.of(new LexicalProjection("chunk_1", text, null, List.of())), "3".repeat(64));
    }

    private static final class InMemoryWork implements VectorProjectionWorkPort {
        private final RevisionProjectionContext context;
        private VectorProjectionPlan plan;
        private VectorEmbeddingWork embeddingWork;
        private VectorBatchArtifactResult embeddingResult;
        private VectorUpsertWork upsertWork;
        private VectorManifestWork manifestWork;
        private VectorProjectionManifestResult manifestResult;
        private ProcessingJobStage nextStage;
        private String nextWorkKey;

        private InMemoryWork(RevisionProjectionContext context) { this.context = context; }

        @Override public Optional<RevisionProjectionContext> findCoordinatorWork(
                String revisionId, WorkerFence fence) { return Optional.of(context); }

        @Override public boolean commitCoordinator(RevisionProjectionContext work, VectorProjectionPlan result,
                                                   List<ProcessingJob> nextJobs, WorkerFence fence) {
            plan = result;
            VectorBatchPlan batch = result.batches().get(0);
            embeddingWork = new VectorEmbeddingWork(context, result.profile(), batch.batchNo(),
                    batch.workKey(), batch.inputFingerprint());
            nextStage = nextJobs.get(0).stage();
            nextWorkKey = nextJobs.get(0).workKey();
            return true;
        }

        @Override public Optional<VectorEmbeddingWork> findEmbeddingWork(
                String revisionId, String workKey, WorkerFence fence) { return Optional.of(embeddingWork); }

        @Override public boolean commitEmbedding(VectorEmbeddingWork work, VectorBatchArtifactResult result,
                                                 ProcessingJob nextJob, WorkerFence fence) {
            embeddingResult = result;
            VectorProjectionTarget target = plan.projections().get(0);
            VectorProjectionMetadata metadata = new VectorProjectionMetadata(target.chunkId(), target.vectorId(),
                    target.projectionFingerprint(), target.chunkType(), target.modality(), 1, target.language());
            upsertWork = new VectorUpsertWork(context, plan.profile(), work.batchNo(), work.workKey(),
                    work.batchInputFingerprint(), result.artifact(), List.of(metadata));
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }

        @Override public Optional<VectorUpsertWork> findUpsertWork(
                String revisionId, String workKey, WorkerFence fence) { return Optional.of(upsertWork); }

        @Override public boolean commitUpsert(VectorUpsertWork work, VectorBatchPayload payload,
                                              ProcessingJob verificationJob, WorkerFence fence) {
            VectorProjectionTarget target = plan.projections().get(0);
            manifestWork = new VectorManifestWork(context, plan.profile(), VectorProjectionRole.PRIMARY,
                    List.of(new VectorProjectionManifestEntry(target.chunkId(), target.vectorId(),
                            target.projectionFingerprint())));
            nextStage = verificationJob.stage();
            nextWorkKey = verificationJob.workKey();
            return true;
        }

        @Override public Optional<VectorManifestWork> findManifestWork(
                String revisionId, String workKey, WorkerFence fence) { return Optional.of(manifestWork); }

        @Override public boolean commitManifest(VectorManifestWork work, VectorProjectionManifestResult result,
                                                ProcessingJob nextJob, WorkerFence fence) {
            manifestResult = result;
            nextStage = nextJob.stage();
            nextWorkKey = nextJob.workKey();
            return true;
        }
    }

    private static final class InMemoryArtifacts implements RevisionArtifactPort {
        private final Map<String, byte[]> bytes = new HashMap<>();
        private final Map<String, StoredArtifact> pins = new HashMap<>();
        @Override public StoredArtifact putImmutable(String key, byte[] content, String type) {
            try {
                String hash = java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
                bytes.putIfAbsent(key, content.clone());
                StoredArtifact pin = new StoredArtifact(key, "v1", hash, content.length, type);
                pins.putIfAbsent(key, pin);
                return pins.get(key);
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        @Override public Optional<StoredArtifact> findImmutable(String key, String type, long maximumBytes) {
            return Optional.ofNullable(pins.get(key));
        }
        @Override public byte[] read(StoredArtifact artifact, long maximumBytes) {
            return bytes.get(artifact.objectKey()).clone();
        }
        @Override public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CountingEmbeddingPort implements EmbeddingPort {
        private final FakeEmbeddingPort delegate;
        private int calls;

        private CountingEmbeddingPort(int dimension) {
            delegate = new FakeEmbeddingPort(dimension);
        }

        @Override public List<float[]> embed(List<String> texts, EmbeddingInputType inputType) {
            calls++;
            return delegate.embed(texts, inputType);
        }
    }

    private static final class InMemoryEmbeddingCache implements org.zipp.ai.domain.retrieval.port.EmbeddingCachePort {
        private final Map<String, float[]> values = new HashMap<>();

        @Override public Optional<float[]> find(String revisionId, String cacheKey, int dimension) {
            float[] cached = values.get(revisionId + ":" + cacheKey);
            return cached == null ? Optional.empty() : Optional.of(cached.clone());
        }

        @Override public void put(String revisionId, String cacheKey, float[] vector) {
            values.putIfAbsent(revisionId + ":" + cacheKey, vector.clone());
        }
    }

    private static final class RecordingQueue implements ProcessingQueuePort {
        @Override public void enqueue(ProcessingJob job) { }
        @Override public Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration duration,
                                                           Set<ProcessingJobStage> stages) { return Optional.empty(); }
        @Override public boolean heartbeat(String jobId, String workerId, long token, Instant now,
                                           Duration extension) { return true; }
        @Override public boolean succeed(String jobId, String workerId, long token) { return true; }
        @Override public boolean retry(String jobId, String workerId, long token, String error, Instant at) {
            return true;
        }
        @Override public boolean fail(String jobId, String workerId, long token, String error) { return true; }
        @Override public int requeueExpiredLeases(Instant now, int limit) { return 0; }
    }
}
