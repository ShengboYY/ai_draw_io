package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.SecureUploadWorkPort;
import org.zipp.ai.ingestion.worker.security.SecureFileValidator;
import org.zipp.ai.ingestion.worker.security.UploadSecurityException;

import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class SecureUploadJobHandler {

    private static final Duration HEARTBEAT_EXTENSION = Duration.ofMinutes(5);

    private final SecureUploadWorkPort uploads;
    private final PinnedQuarantineContentPort quarantineContent;
    private final ProcessingQueuePort queue;
    private final SecureFileValidator validator;
    private final Clock clock;

    public SecureUploadJobHandler(SecureUploadWorkPort uploads,
                                  PinnedQuarantineContentPort quarantineContent,
                                  ProcessingQueuePort queue,
                                  SecureFileValidator validator,
                                  Clock clock) {
        this.uploads = Objects.requireNonNull(uploads, "uploads");
        this.quarantineContent = Objects.requireNonNull(quarantineContent, "quarantineContent");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobOutcome handle(ProcessingJobLease lease) {
        var job = lease.job();
        if (job.stage() != ProcessingJobStage.VALIDATE_OWNERSHIP || job.target().uploadSessionId() == null) {
            return JobOutcome.permanent("UNSUPPORTED_WP2_STAGE");
        }
        String uploadId = job.target().uploadSessionId();
        UploadSession session = uploads.findById(uploadId).orElse(null);
        if (session == null) {
            return JobOutcome.permanent(UploadErrorCode.UPLOAD_NOT_FOUND.name());
        }
        if (session.securityValidated()) {
            return JobOutcome.succeeded();
        }
        if (session.state() == UploadSessionState.REJECTED) {
            // A crash can happen after the fenced rejection commit but before the queue row is
            // failed. A later lease must converge on the persisted terminal result.
            return JobOutcome.permanent(session.errorCode() == null
                    ? UploadErrorCode.UPLOAD_ALREADY_TERMINAL.name() : session.errorCode());
        }
        if (session.state() == UploadSessionState.CANCELLED
                || session.state() == UploadSessionState.EXPIRED
                || session.state() == UploadSessionState.SUCCEEDED) {
            return JobOutcome.permanent(UploadErrorCode.UPLOAD_ALREADY_TERMINAL.name());
        }
        WorkerFence fence = fence(lease);
        if (session.state() == UploadSessionState.OBJECT_VERSION_PINNED) {
            session = uploads.beginProcessing(uploadId, session.generation(), fence).orElse(null);
        }
        if (session == null || session.state() != UploadSessionState.PROCESSING || session.pinnedObject() == null) {
            return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        }
        Path temporaryDirectory = null;
        Path temporaryFile = null;
        try {
            // AWS ResponseTransformer.toFile requires a non-existing destination; a private
            // directory avoids races and keeps cleanup narrowly scoped.
            temporaryDirectory = Files.createTempDirectory("secure-upload-");
            temporaryFile = temporaryDirectory.resolve("payload.quarantine");
            var content = quarantineContent.downloadPinnedVersion(
                    session.quarantineBucket(), session.quarantineKey(), session.pinnedObject().versionId(),
                    session.expectedSize(), temporaryFile);
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            var result = validator.validate(session, content);
            if (!heartbeat(lease)) {
                return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
            }
            ProcessingJob nextJob = ProcessingJob.enqueue("job_" + UUID.randomUUID(),
                    ProcessingJobTarget.forUpload(uploadId), ProcessingJobStage.RESOLVE_CONTENT_DEDUP,
                    "root", sha256(result.actualSha256() + ":" + ProcessingJobStage.RESOLVE_CONTENT_DEDUP.name()),
                    0, clock.instant());
            boolean committed = uploads.commitSecurityValidation(
                    uploadId, session.generation(), result, nextJob, fence(lease));
            return committed ? JobOutcome.succeeded()
                    : JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        } catch (UploadSecurityException e) {
            boolean rejected = uploads.reject(uploadId, session.generation(), e.errorCode(), fence(lease));
            if (rejected) {
                try {
                    quarantineContent.deletePinnedVersion(session.quarantineBucket(), session.quarantineKey(),
                            session.pinnedObject().versionId());
                } catch (RuntimeException ignored) {
                    // The DB rejection blocks reads immediately; lifecycle cleanup retries physical deletion.
                }
            }
            return rejected ? JobOutcome.permanent(e.errorCode().name())
                    : JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
        } catch (RuntimeException e) {
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        } catch (IOException e) {
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The task uses ephemeral storage; a failed cleanup never makes quarantine readable.
                }
            }
            if (temporaryDirectory != null) {
                try {
                    Files.deleteIfExists(temporaryDirectory);
                } catch (IOException ignored) {
                    // Fargate ephemeral storage is discarded with the task.
                }
            }
        }
    }

    private boolean heartbeat(ProcessingJobLease lease) {
        var job = lease.job();
        return queue.heartbeat(job.id(), job.leaseOwner(), lease.fenceToken(), clock.instant(), HEARTBEAT_EXTENSION);
    }

    private WorkerFence fence(ProcessingJobLease lease) {
        return new WorkerFence(lease.job().id(), lease.job().leaseOwner(), lease.fenceToken());
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
