package org.zipp.ai.domain.material.model.aggregate;

import org.zipp.ai.domain.material.model.valobj.MaterialReadLeaseStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Short-lived capability protecting an exact source while an AI run reads it. */
public final class EvidenceReadLease {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration MAX_DURATION = Duration.ofMinutes(15);

    private final String id;
    private final String ownerKey;
    private final String materialId;
    private final String versionId;
    private final String revisionId;
    private final String runId;
    private final Instant createdAt;
    private final Instant maxExpiresAt;
    private MaterialReadLeaseStatus status;
    private Instant expiresAt;

    private EvidenceReadLease(String id, String ownerKey, String materialId, String versionId,
                              String revisionId, String runId, Instant createdAt,
                              Instant expiresAt, Instant maxExpiresAt,
                              MaterialReadLeaseStatus status) {
        this.id = required(id, "id");
        this.ownerKey = required(ownerKey, "ownerKey");
        this.materialId = required(materialId, "materialId");
        this.versionId = required(versionId, "versionId");
        this.revisionId = required(revisionId, "revisionId");
        this.runId = required(runId, "runId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.maxExpiresAt = Objects.requireNonNull(maxExpiresAt, "maxExpiresAt");
        this.status = Objects.requireNonNull(status, "status");
        if (expiresAt.isAfter(maxExpiresAt) || maxExpiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lease expiry is invalid");
        }
    }

    public static EvidenceReadLease issue(String id, String ownerKey, String materialId,
                                          String versionId, String revisionId, String runId,
                                          Instant issuedAt) {
        Instant now = Objects.requireNonNull(issuedAt, "issuedAt");
        return new EvidenceReadLease(id, ownerKey, materialId, versionId, revisionId, runId,
                now, now.plus(LEASE_DURATION), now.plus(MAX_DURATION), MaterialReadLeaseStatus.ACTIVE);
    }

    public static EvidenceReadLease rehydrate(String id, String ownerKey, String materialId,
                                              String versionId, String revisionId, String runId,
                                              Instant createdAt, Instant expiresAt,
                                              Instant maxExpiresAt, MaterialReadLeaseStatus status) {
        return new EvidenceReadLease(id, ownerKey, materialId, versionId, revisionId, runId,
                createdAt, expiresAt, maxExpiresAt, status);
    }

    public void renew(Instant renewedAt) {
        Instant now = Objects.requireNonNull(renewedAt, "renewedAt");
        if (status != MaterialReadLeaseStatus.ACTIVE || !now.isBefore(expiresAt)
                || !now.isBefore(maxExpiresAt)) {
            throw new IllegalStateException("read lease cannot be renewed");
        }
        Instant extended = now.plus(LEASE_DURATION);
        expiresAt = extended.isBefore(maxExpiresAt) ? extended : maxExpiresAt;
    }

    public void release() {
        if (status != MaterialReadLeaseStatus.ACTIVE) {
            throw new IllegalStateException("read lease is not active");
        }
        status = MaterialReadLeaseStatus.RELEASED;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public String id() { return id; }
    public String ownerKey() { return ownerKey; }
    public String materialId() { return materialId; }
    public String versionId() { return versionId; }
    public String revisionId() { return revisionId; }
    public String runId() { return runId; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant maxExpiresAt() { return maxExpiresAt; }
    public MaterialReadLeaseStatus status() { return status; }
}
