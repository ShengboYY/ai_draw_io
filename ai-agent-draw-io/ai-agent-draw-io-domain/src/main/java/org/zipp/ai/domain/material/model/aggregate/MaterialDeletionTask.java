package org.zipp.ai.domain.material.model.aggregate;

import org.zipp.ai.domain.material.model.valobj.MaterialDeletionStage;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionTaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Durable, fenced workflow that never restores readability after deletion starts. */
public final class MaterialDeletionTask {
    private final String id;
    private final String materialId;
    private final long lifecycleGeneration;
    private MaterialDeletionStage stage;
    private MaterialDeletionTaskStatus status;
    private int attempt;
    private Instant notBefore;
    private String leaseOwner;
    private Instant leaseUntil;
    private long fenceToken;
    private String errorCode;

    private MaterialDeletionTask(String id, String materialId, long lifecycleGeneration,
                                 MaterialDeletionStage stage, MaterialDeletionTaskStatus status,
                                 int attempt, Instant notBefore, String leaseOwner,
                                 Instant leaseUntil, long fenceToken, String errorCode) {
        this.id = required(id, "id");
        this.materialId = required(materialId, "materialId");
        if (lifecycleGeneration < 1 || attempt < 0 || fenceToken < 0) {
            throw new IllegalArgumentException("deletion generations are invalid");
        }
        this.lifecycleGeneration = lifecycleGeneration;
        this.stage = Objects.requireNonNull(stage, "stage");
        this.status = Objects.requireNonNull(status, "status");
        this.attempt = attempt;
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.fenceToken = fenceToken;
        this.errorCode = errorCode;
        if (status == MaterialDeletionTaskStatus.RUNNING
                && (leaseOwner == null || leaseUntil == null || fenceToken < 1)) {
            throw new IllegalArgumentException("running deletion task requires a fenced lease");
        }
    }

    public static MaterialDeletionTask rehydrate(String id, String materialId, long lifecycleGeneration,
                                                 MaterialDeletionStage stage,
                                                 MaterialDeletionTaskStatus status, int attempt,
                                                 Instant notBefore, String leaseOwner,
                                                 Instant leaseUntil, long fenceToken,
                                                 String errorCode) {
        return new MaterialDeletionTask(id, materialId, lifecycleGeneration, stage, status, attempt,
                notBefore, leaseOwner, leaseUntil, fenceToken, errorCode);
    }

    public void claim(String workerId, Instant claimedAt, Duration duration) {
        Instant now = Objects.requireNonNull(claimedAt, "claimedAt");
        Duration leaseDuration = Objects.requireNonNull(duration, "duration");
        if ((status != MaterialDeletionTaskStatus.QUEUED && status != MaterialDeletionTaskStatus.RETRY)
                || now.isBefore(notBefore) || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalStateException("deletion task is not claimable");
        }
        status = MaterialDeletionTaskStatus.RUNNING;
        leaseOwner = required(workerId, "workerId");
        leaseUntil = now.plus(leaseDuration);
        attempt++;
        fenceToken++;
    }

    public void advance(MaterialDeletionStage nextStage, Instant readyAt) {
        requireRunning();
        MaterialDeletionStage expected = switch (stage) {
            case WAIT_LEASES -> MaterialDeletionStage.DELETE_VECTORS;
            case DELETE_VECTORS -> MaterialDeletionStage.DELETE_OBJECTS;
            case DELETE_OBJECTS -> MaterialDeletionStage.PURGE_DATABASE;
            case PURGE_DATABASE -> throw new IllegalStateException("purge is the final stage");
        };
        if (nextStage != expected) throw new IllegalArgumentException("deletion stage cannot be skipped");
        stage = nextStage;
        status = MaterialDeletionTaskStatus.QUEUED;
        notBefore = Objects.requireNonNull(readyAt, "readyAt");
        clearLease();
        errorCode = null;
    }

    public void defer(String stableErrorCode, Instant retryAt) {
        requireRunning();
        status = MaterialDeletionTaskStatus.RETRY;
        errorCode = required(stableErrorCode, "stableErrorCode");
        notBefore = Objects.requireNonNull(retryAt, "retryAt");
        clearLease();
    }

    public void complete() {
        requireRunning();
        if (stage != MaterialDeletionStage.PURGE_DATABASE) {
            throw new IllegalStateException("deletion content has not been purged");
        }
        status = MaterialDeletionTaskStatus.SUCCEEDED;
        clearLease();
        errorCode = null;
    }

    private void requireRunning() {
        if (status != MaterialDeletionTaskStatus.RUNNING) {
            throw new IllegalStateException("deletion task does not hold a lease");
        }
    }

    private void clearLease() { leaseOwner = null; leaseUntil = null; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public String id() { return id; }
    public String materialId() { return materialId; }
    public long lifecycleGeneration() { return lifecycleGeneration; }
    public MaterialDeletionStage stage() { return stage; }
    public MaterialDeletionTaskStatus status() { return status; }
    public int attempt() { return attempt; }
    public Instant notBefore() { return notBefore; }
    public String leaseOwner() { return leaseOwner; }
    public Instant leaseUntil() { return leaseUntil; }
    public long fenceToken() { return fenceToken; }
    public String errorCode() { return errorCode; }
}
