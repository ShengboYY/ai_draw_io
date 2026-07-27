package org.zipp.ai.ingestion.worker.fake;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionPortFakeContractTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void fakeBlobAndQueuePreserveImmutabilityAndFenceSemantics() {
        FakeBlobStore blobs = new FakeBlobStore();
        byte[] original = new byte[]{1, 2, 3};
        blobs.put(new MaterialObject("objects/one", "sha256:one", original));
        original[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, blobs.get("objects/one").orElseThrow().content());

        FakeProcessingQueue queue = new FakeProcessingQueue();
        queue.enqueue(ProcessingJob.enqueue(
                "job_1", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.OCR_SELECTED_PAGES, "page:1", "sha256:input", 1, NOW));
        var lease = queue.claim("worker-1", NOW, Duration.ofMinutes(2)).orElseThrow();
        assertTrue(queue.succeed("job_1", "worker-1", lease.fenceToken()));
        assertFalse(queue.succeed("job_1", "worker-1", lease.fenceToken()));
    }

    @Test
    void fakeModelAndProjectionPortsAreDeterministicAndKeepTextOutOfVectorRecords() {
        FakeEmbeddingPort embedding = new FakeEmbeddingPort(4);
        List<float[]> vectors = embedding.embed(List.of("agile flow"), EmbeddingInputType.PASSAGE);
        assertEquals(4, vectors.get(0).length);

        FakeRetrievalVectorIndex index = new FakeRetrievalVectorIndex();
        index.upsert(List.of(new VectorProjection(
                "rc_1", "ig_1", "vec_1", vectors.get(0),
                Map.of("tenant_key", "tenant_1", "retrieval_chunk_id", "rc_1"))));
        assertEquals(List.of("rc_1"), index.query(vectors.get(0), "tenant_1", 5));
        assertFalse(index.serializedRecords().contains("agile flow"));
    }

    @Test
    void workersOnlyClaimStagesTheyOwn() {
        FakeProcessingQueue queue = new FakeProcessingQueue();
        queue.enqueue(ProcessingJob.enqueue("job-security", ProcessingJobTarget.forUpload("upl_1"),
                ProcessingJobStage.VALIDATE_OWNERSHIP, "root", "a".repeat(64), 0, NOW));
        queue.enqueue(ProcessingJob.enqueue("job-dedup", ProcessingJobTarget.forUpload("upl_1"),
                ProcessingJobStage.RESOLVE_CONTENT_DEDUP, "root", "b".repeat(64), 10, NOW));

        var claimed = queue.claim("security-worker", NOW, Duration.ofMinutes(2),
                Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP)).orElseThrow();

        assertEquals(ProcessingJobStage.VALIDATE_OWNERSHIP, claimed.job().stage());
    }

    @Test
    void fakeContentPortsExposeConfiguredResultsWithoutExternalIo() {
        MaterialObject object = new MaterialObject("objects/one", "sha256:one", new byte[]{1});
        assertTrue(new FakeMalwareScanner(true).scan(object).clean());
        assertEquals(2, new FakeDocumentParser(2).parse(
                java.nio.file.Path.of("fake.pdf"), "application/pdf", java.nio.file.Path.of("fake-pages"))
                .pageCount());
        assertEquals("ocr-page-1", new FakeOcrEngine().recognize(java.nio.file.Path.of("page.png"), 1).text());
        assertEquals("diagram", new FakeVisionAnalyzer().analyze(object, 1, "region:1").kind());
    }
}
