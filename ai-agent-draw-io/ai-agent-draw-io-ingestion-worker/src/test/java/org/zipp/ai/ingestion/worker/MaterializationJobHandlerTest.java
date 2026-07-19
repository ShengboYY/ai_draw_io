package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationIds;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationResult;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.MaterializationWorkPort;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.ingestion.worker.fake.FakeProcessingQueue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterializationJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");

    @Test
    void resolveStageMaterializesThroughTheFencedTransaction() {
        FakeMaterializationWork work = new FakeMaterializationWork();
        FakeProcessingQueue queue = new FakeProcessingQueue();
        ProcessingJobLease lease = lease(queue, "job_resolve", ProcessingJobStage.RESOLVE_CONTENT_DEDUP);
        MaterializationJobHandler handler = new MaterializationJobHandler(
                work, ignored -> { throw new AssertionError("resolve must not copy S3 content"); },
                (bucket, key, version, maximum, destination) -> {
                    throw new AssertionError("resolve must not download quarantine content");
                }, queue, Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.SUCCEEDED, outcome.kind());
        assertEquals(1, work.resolveCalls);
    }

    @Test
    void promotionStageCopiesThePinnedVersionBeforeTheFencedCommit() {
        OriginalPromotionWork promotionWork = new OriginalPromotionWork(
                "upl_1", 3, "quarantine", "incoming/opaque", "s3-source-version",
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, "a".repeat(64), null);
        FakeMaterializationWork work = new FakeMaterializationWork();
        work.promotionWork = promotionWork;
        FakeProcessingQueue queue = new FakeProcessingQueue();
        ProcessingJobLease lease = lease(queue, "job_promote", ProcessingJobStage.PROMOTE_ORIGINAL);
        MaterializationJobHandler handler = new MaterializationJobHandler(
                work, ignored -> new PromotedOriginal(
                "original/owner/blob", "s3-destination-version", "etag", "checksum", 42),
                (bucket, key, version, maximum, destination) -> {
                    throw new AssertionError("S3 server-side promotion must not download the object");
                }, queue, Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.SUCCEEDED, outcome.kind());
        assertTrue(work.promotionCommitted);
    }

    @Test
    void availableBlobUsesTheFixedFormalVersionWithoutCopyingAgain() {
        PromotedOriginal fixed = new PromotedOriginal(
                "original/owner/blob", "fixed-version", "etag", "checksum", 42);
        OriginalPromotionWork promotionWork = new OriginalPromotionWork(
                "upl_1", 3, "quarantine", "incoming/opaque", "s3-source-version",
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, "a".repeat(64), fixed);
        FakeMaterializationWork work = new FakeMaterializationWork();
        work.promotionWork = promotionWork;
        FakeProcessingQueue queue = new FakeProcessingQueue();
        ProcessingJobLease lease = lease(queue, "job_promote", ProcessingJobStage.PROMOTE_ORIGINAL);
        MaterializationJobHandler handler = new MaterializationJobHandler(
                work, ignored -> { throw new AssertionError("an available blob must not be copied again"); },
                (bucket, key, version, maximum, destination) -> null,
                queue, Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.SUCCEEDED, outcome.kind());
        assertEquals("fixed-version", work.committedOriginal.objectVersionId());
    }

    @Test
    void losingConcurrentCopyIsDiscardedWhenTheDatabaseCommitLosesItsFence() {
        OriginalPromotionWork promotionWork = new OriginalPromotionWork(
                "upl_1", 3, "quarantine", "incoming/opaque", "s3-source-version",
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, "a".repeat(64), null);
        PromotedOriginal copied = new PromotedOriginal(
                "original/owner/blob", "loser-version", "etag", "checksum", 42);
        FakeMaterializationWork work = new FakeMaterializationWork();
        work.promotionWork = promotionWork;
        work.commitResult = false;
        AtomicReference<PromotedOriginal> discarded = new AtomicReference<>();
        OriginalPromotionPort promotion = new OriginalPromotionPort() {
            @Override
            public PromotedOriginal promote(OriginalPromotionWork ignored) {
                return copied;
            }

            @Override
            public void discard(PromotedOriginal promotedOriginal) {
                discarded.set(promotedOriginal);
            }
        };
        FakeProcessingQueue queue = new FakeProcessingQueue();
        ProcessingJobLease lease = lease(queue, "job_promote", ProcessingJobStage.PROMOTE_ORIGINAL);
        MaterializationJobHandler handler = new MaterializationJobHandler(
                work, promotion, (bucket, key, version, maximum, destination) -> null,
                queue, Clock.fixed(NOW, ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.TRANSIENT_FAILURE, outcome.kind());
        assertEquals("loser-version", discarded.get().objectVersionId());
    }

    private static ProcessingJobLease lease(FakeProcessingQueue queue, String jobId, ProcessingJobStage stage) {
        queue.enqueue(ProcessingJob.enqueue(jobId, ProcessingJobTarget.forUpload("upl_1"), stage,
                "root", "f".repeat(64), 0, NOW));
        return queue.claim("worker-1", NOW, Duration.ofMinutes(5)).orElseThrow();
    }

    private static final class FakeMaterializationWork implements MaterializationWorkPort {
        private int resolveCalls;
        private OriginalPromotionWork promotionWork;
        private boolean promotionCommitted;
        private boolean commitResult = true;

        @Override
        public MaterializationResult resolveAndMaterialize(String uploadId, MaterializationIds ids,
                                                            String processingFingerprint,
                                                            ProcessingJob promotionJob,
                                                            ProcessingJob extractionJob,
                                                            WorkerFence fence, Instant now) {
            resolveCalls++;
            return new MaterializationResult(MaterializationResult.Outcome.PROMOTION_QUEUED,
                    ids.materialId(), ids.versionId(), ids.revisionId());
        }

        @Override
        public Optional<OriginalPromotionWork> findPromotionWork(String uploadId, WorkerFence fence) {
            return Optional.ofNullable(promotionWork);
        }

        @Override
        public boolean commitPromotion(OriginalPromotionWork work, PromotedOriginal promoted,
                                       ProcessingJob extractionJob, WorkerFence fence) {
            promotionCommitted = true;
            committedOriginal = promoted;
            return commitResult;
        }

        private PromotedOriginal committedOriginal;
    }
}
