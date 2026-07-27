package org.zipp.ai.domain.ingestion.model.aggregate;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public final class UploadSession {

    private final String id;
    private final OwnerType ownerType;
    private final String ownerKey;
    private final String idempotencyKey;
    private final String displayName;
    private final String declaredMediaType;
    private final long expectedSize;
    private final String expectedSha256;
    private final UploadTarget target;
    private final String newVersionOfMaterialId;
    private final String quarantineBucket;
    private final String quarantineKey;
    private final Instant policyExpiresAt;
    private final Instant createdAt;
    private UploadSessionState state = UploadSessionState.CREATED;
    private QuarantineObjectVersion pinnedObject;
    private long generation;
    private String materialId;
    private String versionId;
    private String errorCode;
    private boolean securityValidated;

    private UploadSession(String id, OwnerType ownerType, String ownerKey, String idempotencyKey,
                          String displayName, String declaredMediaType, long expectedSize,
                          String expectedSha256, UploadTarget target, String newVersionOfMaterialId,
                          String quarantineBucket, String quarantineKey, Instant policyExpiresAt,
                          Instant createdAt) {
        this.id = requireText(id, "id", 64);
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.ownerKey = requireText(ownerKey, "ownerKey", 64);
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 128);
        this.displayName = requireText(displayName, "displayName", 512);
        this.declaredMediaType = requireText(declaredMediaType, "declaredMediaType", 128)
                .toLowerCase(Locale.ROOT);
        if (expectedSize < 1) {
            throw new IllegalArgumentException("expectedSize must be positive");
        }
        this.expectedSize = expectedSize;
        this.expectedSha256 = requireSha256(expectedSha256);
        this.target = Objects.requireNonNull(target, "target");
        this.newVersionOfMaterialId = optionalText(newVersionOfMaterialId, "newVersionOfMaterialId", 64);
        this.quarantineBucket = requireText(quarantineBucket, "quarantineBucket", 255);
        this.quarantineKey = requireText(quarantineKey, "quarantineKey", 1024);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.policyExpiresAt = Objects.requireNonNull(policyExpiresAt, "policyExpiresAt");
        if (!policyExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("policy expiry must be after creation");
        }
    }

    public static UploadSession create(String id, OwnerType ownerType, String ownerKey, String idempotencyKey,
                                       String displayName, String declaredMediaType, long expectedSize,
                                       String expectedSha256, UploadTarget target, String newVersionOfMaterialId,
                                       String quarantineBucket, String quarantineKey, Instant policyExpiresAt,
                                       Instant createdAt) {
        return new UploadSession(id, ownerType, ownerKey, idempotencyKey, displayName, declaredMediaType,
                expectedSize, expectedSha256, target, newVersionOfMaterialId, quarantineBucket,
                quarantineKey, policyExpiresAt, createdAt);
    }

    public static UploadSession rehydrate(UploadSessionSnapshot snapshot) {
        UploadSessionSnapshot source = Objects.requireNonNull(snapshot, "snapshot");
        UploadSession session = new UploadSession(
                source.id(), source.ownerType(), source.ownerKey(), source.idempotencyKey(), source.displayName(),
                source.declaredMediaType(), source.expectedSize(), source.expectedSha256(), source.target(),
                source.newVersionOfMaterialId(), source.quarantineBucket(), source.quarantineKey(),
                source.policyExpiresAt(), source.createdAt());
        session.state = Objects.requireNonNull(source.state(), "state");
        session.pinnedObject = source.pinnedObject();
        session.generation = source.generation();
        session.materialId = trimToNull(source.materialId());
        session.versionId = trimToNull(source.versionId());
        session.errorCode = trimToNull(source.errorCode());
        session.securityValidated = source.securityValidated();
        if (session.state != UploadSessionState.CREATED && session.state != UploadSessionState.EXPIRED
                && session.state != UploadSessionState.CANCELLED && session.pinnedObject == null) {
            throw new IllegalArgumentException("pinned object is required after upload completion");
        }
        return session;
    }

    public boolean pinObject(QuarantineObjectVersion objectVersion, Instant pinnedAt) {
        if (state != UploadSessionState.CREATED) {
            // Complete is idempotent: an already pinned session never follows a newer replayed version.
            return false;
        }
        Instant now = Objects.requireNonNull(pinnedAt, "pinnedAt");
        if (expire(now)) {
            throw new IllegalStateException("upload policy has expired");
        }
        QuarantineObjectVersion candidate = Objects.requireNonNull(objectVersion, "objectVersion");
        if (candidate.byteSize() != expectedSize) {
            throw new IllegalArgumentException("uploaded object size does not match declaration");
        }
        pinnedObject = candidate;
        state = UploadSessionState.OBJECT_VERSION_PINNED;
        generation++;
        return true;
    }

    public boolean expire(Instant now) {
        Instant checkedAt = Objects.requireNonNull(now, "now");
        if (state != UploadSessionState.CREATED || checkedAt.isBefore(policyExpiresAt)) {
            return false;
        }
        state = UploadSessionState.EXPIRED;
        generation++;
        return true;
    }

    public boolean beginProcessing() {
        if (state != UploadSessionState.OBJECT_VERSION_PINNED) {
            return false;
        }
        state = UploadSessionState.PROCESSING;
        generation++;
        return true;
    }

    public boolean succeed(String materialId, String versionId) {
        if (state != UploadSessionState.PROCESSING) {
            return false;
        }
        this.materialId = requireText(materialId, "materialId", 64);
        this.versionId = requireText(versionId, "versionId", 64);
        state = UploadSessionState.SUCCEEDED;
        generation++;
        return true;
    }

    public boolean reject(UploadErrorCode reason) {
        if (state != UploadSessionState.PROCESSING) {
            return false;
        }
        UploadErrorCode rejection = Objects.requireNonNull(reason, "reason");
        if (rejection != UploadErrorCode.REJECTED_SECURITY
                && rejection != UploadErrorCode.REJECTED_LIMIT
                && rejection != UploadErrorCode.REJECTED_FORMAT) {
            throw new IllegalArgumentException("a terminal rejection code is required");
        }
        errorCode = rejection.name();
        state = UploadSessionState.REJECTED;
        generation++;
        return true;
    }

    public boolean cancel() {
        if (state != UploadSessionState.CREATED && state != UploadSessionState.OBJECT_VERSION_PINNED) {
            return false;
        }
        state = UploadSessionState.CANCELLED;
        generation++;
        return true;
    }

    private static String requireSha256(String value) {
        String normalized = requireText(value, "expectedSha256").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256 must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = requireText(value, field);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maximumLength) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String id() { return id; }
    public OwnerType ownerType() { return ownerType; }
    public String ownerKey() { return ownerKey; }
    public String idempotencyKey() { return idempotencyKey; }
    public String displayName() { return displayName; }
    public String declaredMediaType() { return declaredMediaType; }
    public long expectedSize() { return expectedSize; }
    public String expectedSha256() { return expectedSha256; }
    public UploadTarget target() { return target; }
    public String newVersionOfMaterialId() { return newVersionOfMaterialId; }
    public String quarantineBucket() { return quarantineBucket; }
    public String quarantineKey() { return quarantineKey; }
    public Instant policyExpiresAt() { return policyExpiresAt; }
    public Instant createdAt() { return createdAt; }
    public UploadSessionState state() { return state; }
    public QuarantineObjectVersion pinnedObject() { return pinnedObject; }
    public long generation() { return generation; }
    public String materialId() { return materialId; }
    public String versionId() { return versionId; }
    public String errorCode() { return errorCode; }
    public boolean securityValidated() { return securityValidated; }
}
