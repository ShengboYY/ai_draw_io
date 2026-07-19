package org.zipp.ai.domain.ingestion.model.aggregate;

import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStatus;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProcessingJob {

    private final String id;
    private final ProcessingJobTarget target;
    private final ProcessingJobStage stage;
    private final String workKey;
    private final String inputFingerprint;
    private final int priority;
    private ProcessingJobStatus status = ProcessingJobStatus.QUEUED;
    private int attempt;
    private Instant notBefore;
    private String leaseOwner;
    private Instant leaseUntil;
    private long fenceToken;
    private String lastErrorCode;

    private ProcessingJob(String id, ProcessingJobTarget target, ProcessingJobStage stage, String workKey,
                          String inputFingerprint, int priority, Instant notBefore) {
        this.id = requireText(id, "id");
        this.target = Objects.requireNonNull(target, "target");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.workKey = requireText(workKey, "workKey");
        this.inputFingerprint = requireText(inputFingerprint, "inputFingerprint");
        this.priority = priority;
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
    }

    public static ProcessingJob enqueue(String id, ProcessingJobTarget target, ProcessingJobStage stage, String workKey,
                                        String inputFingerprint, int priority, Instant notBefore) {
        return new ProcessingJob(id, target, stage, workKey, inputFingerprint, priority, notBefore);
    }

    public long claim(String workerId, Instant claimedAt, Duration leaseDuration) {
        if (status != ProcessingJobStatus.QUEUED && status != ProcessingJobStatus.RETRY) {
            throw new IllegalStateException("job is not claimable");
        }
        Instant now = Objects.requireNonNull(claimedAt, "claimedAt");
        Duration duration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (now.isBefore(notBefore)) {
            throw new IllegalStateException("job is not ready yet");
        }
        status = ProcessingJobStatus.RUNNING;
        leaseOwner = requireText(workerId, "workerId");
        leaseUntil = now.plus(duration);
        attempt++;
        fenceToken++;
        return fenceToken;
    }

    public boolean heartbeat(String workerId, long expectedFence, Instant now, Duration extension) {
        if (!matchesLease(workerId, expectedFence)) {
            return false;
        }
        Instant heartbeatAt = Objects.requireNonNull(now, "now");
        Duration duration = Objects.requireNonNull(extension, "extension");
        if (duration.isZero() || duration.isNegative() || !heartbeatAt.isBefore(leaseUntil)) {
            return false;
        }
        leaseUntil = heartbeatAt.plus(duration);
        return true;
    }

    public boolean succeed(String workerId, long expectedFence) {
        if (!matchesLease(workerId, expectedFence)) {
            return false;
        }
        status = ProcessingJobStatus.SUCCEEDED;
        clearLease();
        return true;
    }

    public boolean retry(String workerId, long expectedFence, String errorCode, Instant retryAt) {
        if (!matchesLease(workerId, expectedFence)) {
            return false;
        }
        status = ProcessingJobStatus.RETRY;
        lastErrorCode = requireText(errorCode, "errorCode");
        notBefore = Objects.requireNonNull(retryAt, "retryAt");
        clearLease();
        return true;
    }

    public boolean fail(String workerId, long expectedFence, String errorCode) {
        if (!matchesLease(workerId, expectedFence)) {
            return false;
        }
        status = ProcessingJobStatus.FAILED;
        lastErrorCode = requireText(errorCode, "errorCode");
        clearLease();
        return true;
    }

    public boolean retryExpiredLease(Instant reapedAt) {
        Instant now = Objects.requireNonNull(reapedAt, "reapedAt");
        if (status != ProcessingJobStatus.RUNNING || now.isBefore(leaseUntil)) {
            return false;
        }
        status = ProcessingJobStatus.RETRY;
        notBefore = now;
        clearLease();
        return true;
    }

    private boolean matchesLease(String workerId, long expectedFence) {
        return status == ProcessingJobStatus.RUNNING
                && Objects.equals(leaseOwner, workerId)
                && fenceToken == expectedFence;
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public ProcessingJobTarget target() { return target; }
    public ProcessingJobStage stage() { return stage; }
    public String workKey() { return workKey; }
    public String inputFingerprint() { return inputFingerprint; }
    public int priority() { return priority; }
    public ProcessingJobStatus status() { return status; }
    public int attempt() { return attempt; }
    public Instant notBefore() { return notBefore; }
    public String leaseOwner() { return leaseOwner; }
    public Instant leaseUntil() { return leaseUntil; }
    public long fenceToken() { return fenceToken; }
    public String lastErrorCode() { return lastErrorCode; }
}
