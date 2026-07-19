package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationIds;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.MaterializationWorkPort;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class MaterializationJobHandler {

    private static final String PROCESSING_FINGERPRINT = sha256(
            sha256("extract=pdfbox-3.0.8:pdfbox-default-v1")
                    + sha256("ocr=tesseract-5:lstm-eng-chi_sim:render-v1")
                    + sha256("clean=canonical-v1:normalization-v1:boilerplate-v1")
                    + sha256("structure=layout-heuristic-v1:section-v1")
                    + sha256("visual=multimodal-provider-v1:visual-schema-v1:budget-v1")
                    + sha256("chunk=chunk-v1:tokenizer-v1:structure-aware-v1")
                    + sha256("lexical=mysql-word-cjk2-v1:exact-term-v1")
                    + "[]");
    private static final Duration HEARTBEAT_EXTENSION = Duration.ofMinutes(5);

    private final MaterializationWorkPort work;
    private final OriginalPromotionPort promotion;
    private final PinnedQuarantineContentPort quarantine;
    private final ProcessingQueuePort queue;
    private final Clock clock;

    public MaterializationJobHandler(MaterializationWorkPort work,
                                     OriginalPromotionPort promotion,
                                     PinnedQuarantineContentPort quarantine,
                                     ProcessingQueuePort queue,
                                     Clock clock) {
        this.work = Objects.requireNonNull(work, "work");
        this.promotion = Objects.requireNonNull(promotion, "promotion");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobOutcome handle(ProcessingJobLease lease) {
        var job = Objects.requireNonNull(lease, "lease").job();
        if (job.target().uploadSessionId() == null) {
            return JobOutcome.permanent("UNSUPPORTED_WP3_TARGET");
        }
        try {
            return switch (job.stage()) {
                case RESOLVE_CONTENT_DEDUP -> resolve(job.target().uploadSessionId(), lease);
                case PROMOTE_ORIGINAL -> promote(job.target().uploadSessionId(), lease);
                default -> JobOutcome.permanent("UNSUPPORTED_WP3_STAGE");
            };
        } catch (RuntimeException e) {
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        }
    }

    private JobOutcome resolve(String uploadId, ProcessingJobLease lease) {
        MaterializationIds ids = new MaterializationIds(
                id("mat_"), id("ver_"), id("blob_"), id("rev_"), id("scope_"));
        ProcessingJob promotionJob = ProcessingJob.enqueue(id("job_"),
                ProcessingJobTarget.forUpload(uploadId), ProcessingJobStage.PROMOTE_ORIGINAL,
                "root", sha256(uploadId + ":" + ProcessingJobStage.PROMOTE_ORIGINAL), 0, clock.instant());
        ProcessingJob extractionJob = ProcessingJob.enqueue(id("job_"),
                ProcessingJobTarget.forRevision(ids.revisionId()), ProcessingJobStage.EXTRACT_NATIVE,
                "root", sha256(ids.revisionId() + ":" + PROCESSING_FINGERPRINT), 0, clock.instant());
        work.resolveAndMaterialize(uploadId, ids, PROCESSING_FINGERPRINT,
                promotionJob, extractionJob, fence(lease), clock.instant());
        return JobOutcome.succeeded();
    }

    private JobOutcome promote(String uploadId, ProcessingJobLease lease) {
        var promotionWork = work.findPromotionWork(uploadId, fence(lease)).orElse(null);
        if (promotionWork == null) {
            // A replay after the fenced commit has no remaining promotion work.
            return JobOutcome.succeeded();
        }
        if (!heartbeat(lease)) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        // A shared blob may have been fixed by another upload; never create or publish a newer S3 version.
        boolean copied = promotionWork.fixedOriginal() == null;
        var promoted = copied
                ? promotion.promote(promotionWork) : promotionWork.fixedOriginal();
        if (promoted.byteSize() != promotionWork.byteSize()
                || !promoted.objectKey().equals(promotionWork.destinationKey())) {
            discardIfCopied(copied, promoted);
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        }
        if (!heartbeat(lease)) {
            discardIfCopied(copied, promoted);
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        ProcessingJob extractionJob = ProcessingJob.enqueue(id("job_"),
                ProcessingJobTarget.forRevision(promotionWork.revisionId()), ProcessingJobStage.EXTRACT_NATIVE,
                "root", sha256(promotionWork.revisionId() + ":" + PROCESSING_FINGERPRINT),
                0, clock.instant());
        boolean committed;
        try {
            committed = work.commitPromotion(promotionWork, promoted, extractionJob, fence(lease));
        } catch (RuntimeException e) {
            discardIfCopied(copied, promoted);
            throw e;
        }
        if (!committed) {
            discardIfCopied(copied, promoted);
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        try {
            quarantine.deletePinnedVersion(promotionWork.quarantineBucket(), promotionWork.quarantineKey(),
                    promotionWork.quarantineVersionId());
        } catch (RuntimeException ignored) {
            // The formal object and DB state are authoritative; lifecycle cleanup removes a stale source copy.
        }
        return JobOutcome.succeeded();
    }

    private void discardIfCopied(boolean copied, PromotedOriginal promoted) {
        if (copied) {
            try {
                promotion.discard(promoted);
            } catch (RuntimeException ignored) {
                // Cleanup is best effort; retry/reconciliation must not hide the authoritative DB outcome.
            }
        }
    }

    private boolean heartbeat(ProcessingJobLease lease) {
        var job = lease.job();
        return queue.heartbeat(job.id(), job.leaseOwner(), lease.fenceToken(),
                clock.instant(), HEARTBEAT_EXTENSION);
    }

    private static WorkerFence fence(ProcessingJobLease lease) {
        return new WorkerFence(lease.job().id(), lease.job().leaseOwner(), lease.fenceToken());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID();
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
