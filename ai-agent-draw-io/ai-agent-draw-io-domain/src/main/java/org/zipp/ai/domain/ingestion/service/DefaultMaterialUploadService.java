package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.exception.UploadAdmissionException;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.CompleteUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadResult;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.UploadAdmissionRequest;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadQuotaSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionStatus;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import org.zipp.ai.domain.ingestion.port.UploadPolicySignerPort;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.ingestion.port.MaterialUploadTelemetry;
import org.zipp.ai.domain.operations.CapacityWorkload;
import org.zipp.ai.domain.operations.MaterialCapacityBreaker;
import org.zipp.ai.domain.operations.MaterialCapacitySnapshot;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialReleaseApproval;
import org.zipp.ai.domain.operations.MaterialRolloutGate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

public final class DefaultMaterialUploadService implements IMaterialUploadService {

    private static final Duration POLICY_TTL = Duration.ofMinutes(10);

    private final UploadSessionStore sessionStore;
    private final QuarantineObjectPort quarantineObjects;
    private final UploadPolicySignerPort policySigner;
    private final UploadScopeAuthorizer scopeAuthorizer;
    private final UploadAdmissionPolicy admissionPolicy;
    private final UploadIdFactory idFactory;
    private final Clock clock;
    private final String quarantineBucket;
    private final MaterialCapacityBreaker capacityBreaker;
    private final MaterialRolloutGate rolloutGate;
    private final MaterialUploadTelemetry telemetry;

    public DefaultMaterialUploadService(UploadSessionStore sessionStore,
                                        QuarantineObjectPort quarantineObjects,
                                        UploadPolicySignerPort policySigner,
                                        UploadScopeAuthorizer scopeAuthorizer,
                                        UploadAdmissionPolicy admissionPolicy,
                                        UploadIdFactory idFactory,
                                        Clock clock,
                                        String quarantineBucket) {
        this(sessionStore, quarantineObjects, policySigner, scopeAuthorizer, admissionPolicy,
                idFactory, clock, quarantineBucket, new MaterialCapacityBreaker(
                        () -> new MaterialCapacitySnapshot(0, 0, 0, 0, true)));
    }

    public DefaultMaterialUploadService(UploadSessionStore sessionStore,
                                        QuarantineObjectPort quarantineObjects,
                                        UploadPolicySignerPort policySigner,
                                        UploadScopeAuthorizer scopeAuthorizer,
                                        UploadAdmissionPolicy admissionPolicy,
                                        UploadIdFactory idFactory,
                                        Clock clock,
                                        String quarantineBucket,
                                        MaterialCapacityBreaker capacityBreaker) {
        this(sessionStore, quarantineObjects, policySigner, scopeAuthorizer, admissionPolicy,
                idFactory, clock, quarantineBucket, capacityBreaker,
                new MaterialRolloutGate(MaterialFeatureSet.allEnabled(),
                        new MaterialReleaseApproval(false, "")),
                MaterialUploadTelemetry.NOOP);
    }

    public DefaultMaterialUploadService(UploadSessionStore sessionStore,
                                        QuarantineObjectPort quarantineObjects,
                                        UploadPolicySignerPort policySigner,
                                        UploadScopeAuthorizer scopeAuthorizer,
                                        UploadAdmissionPolicy admissionPolicy,
                                        UploadIdFactory idFactory,
                                        Clock clock,
                                        String quarantineBucket,
                                        MaterialCapacityBreaker capacityBreaker,
                                        MaterialRolloutGate rolloutGate) {
        this(sessionStore, quarantineObjects, policySigner, scopeAuthorizer, admissionPolicy,
                idFactory, clock, quarantineBucket, capacityBreaker, rolloutGate, MaterialUploadTelemetry.NOOP);
    }

    public DefaultMaterialUploadService(UploadSessionStore sessionStore,
                                        QuarantineObjectPort quarantineObjects,
                                        UploadPolicySignerPort policySigner,
                                        UploadScopeAuthorizer scopeAuthorizer,
                                        UploadAdmissionPolicy admissionPolicy,
                                        UploadIdFactory idFactory,
                                        Clock clock,
                                        String quarantineBucket,
                                        MaterialCapacityBreaker capacityBreaker,
                                        MaterialRolloutGate rolloutGate,
                                        MaterialUploadTelemetry telemetry) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.quarantineObjects = Objects.requireNonNull(quarantineObjects, "quarantineObjects");
        this.policySigner = Objects.requireNonNull(policySigner, "policySigner");
        this.scopeAuthorizer = Objects.requireNonNull(scopeAuthorizer, "scopeAuthorizer");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.quarantineBucket = requireText(quarantineBucket, "quarantineBucket");
        this.capacityBreaker = Objects.requireNonNull(capacityBreaker, "capacityBreaker");
        this.rolloutGate = Objects.requireNonNull(rolloutGate, "rolloutGate");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public InitiateUploadResult initiate(InitiateUploadCommand command) {
        InitiateUploadCommand request = Objects.requireNonNull(command, "command");
        String ownerKey = requireText(request.ownerKey(), "ownerKey");
        String idempotencyKey = requireText(request.idempotencyKey(), "idempotencyKey");
        var existing = sessionStore.findByOwnerAndIdempotencyKey(
                Objects.requireNonNull(request.ownerType(), "ownerType"), ownerKey, idempotencyKey);
        if (existing.isPresent()) {
            UploadSession session = existing.get();
            if (session.expire(clock.instant())) {
                session = sessionStore.expire(session);
            }
            recordUpload(request, "replay");
            return new InitiateUploadResult(session.id(), session.state(),
                    session.state() == UploadSessionState.CREATED ? policySigner.sign(session) : null);
        }
        // Rollout and capacity never invalidate an accepted idempotent session or running safety work.
        if (request.ownerType() == org.zipp.ai.domain.account.model.valobj.OwnerType.ANONYMOUS
                && !rolloutGate.anonymousUploadAllowed()) {
            throw rejected(request, UploadErrorCode.ANONYMOUS_RELEASE_NOT_APPROVED);
        }
        if (!capacityBreaker.decide(request.ownerType(), CapacityWorkload.NEW_UPLOAD).allowed()) {
            throw rejected(request, UploadErrorCode.CAPACITY_EXHAUSTED);
        }
        var target = scopeAuthorizer.canonicalTarget(request.ownerType(), ownerKey, request.target(),
                request.contextDiagramId());
        if (!scopeAuthorizer.canUpload(request.ownerType(), ownerKey, target, request.contextDiagramId(),
                request.newVersionOfMaterialId())) {
            throw rejected(request, UploadErrorCode.UPLOAD_SCOPE_FORBIDDEN);
        }
        Instant now = clock.instant();
        Instant hourBucket = now.truncatedTo(ChronoUnit.HOURS);
        UploadQuotaSnapshot quota = sessionStore.quotaSnapshot(
                request.ownerType(), ownerKey, requireText(request.ipRateKey(), "ipRateKey"), hourBucket);
        try {
            admissionPolicy.validate(new UploadAdmissionRequest(
                    request.ownerType(), target, request.declaredMediaType(), request.byteSize(),
                    quota.accountOriginalBytes(), quota.activeFileCount(), quota.processingCount(),
                    quota.workspaceHourlyCount(), quota.ipHourlyCount(), request.batchFileCount()));
        } catch (UploadAdmissionException rejected) {
            recordUpload(request, rejected.code().name());
            throw rejected;
        }

        String uploadId = idFactory.nextUploadId();
        String objectKey = "incoming/" + idFactory.ownerPathToken(ownerKey) + "/"
                + uploadId + "/" + idFactory.nextObjectId();
        UploadSession session = UploadSession.create(
                uploadId, request.ownerType(), ownerKey, idempotencyKey,
                request.displayName(), request.declaredMediaType(), request.byteSize(), request.sha256(),
                target, request.newVersionOfMaterialId(), quarantineBucket, objectKey,
                now.plus(POLICY_TTL), now);
        // The store serializes quota reservation and the idempotency insert. A concurrent replay
        // returns the winning aggregate and therefore cannot consume quota twice.
        UploadSession persisted = sessionStore.createAndConsumeRate(
                session, request.ipRateKey(), hourBucket, admissionPolicy.rateReservation(request.ownerType()));
        recordUpload(request, "accepted");
        return new InitiateUploadResult(persisted.id(), persisted.state(),
                persisted.state() == UploadSessionState.CREATED ? policySigner.sign(persisted) : null);
    }

    @Override
    public UploadSessionStatus complete(CompleteUploadCommand command) {
        CompleteUploadCommand request = Objects.requireNonNull(command, "command");
        UploadSession session = requiredSession(request);
        if (session.state() != UploadSessionState.CREATED) {
            return statusOf(session);
        }
        var objectVersion = quarantineObjects.headLatestVersion(
                        session.quarantineBucket(), session.quarantineKey())
                .orElseThrow(() -> new UploadAdmissionException(UploadErrorCode.UPLOAD_OBJECT_MISSING));
        try {
            session.pinObject(objectVersion, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new UploadAdmissionException(UploadErrorCode.UPLOAD_OBJECT_SIZE_MISMATCH);
        } catch (IllegalStateException e) {
            UploadSession persisted = sessionStore.expire(session);
            if (persisted.state() != UploadSessionState.EXPIRED) {
                return statusOf(persisted);
            }
            throw new UploadAdmissionException(UploadErrorCode.UPLOAD_POLICY_EXPIRED);
        }
        String fingerprint = sha256(session.id() + ":" + objectVersion.versionId()
                + ":" + ProcessingJobStage.VALIDATE_OWNERSHIP.name());
        ProcessingJob job = ProcessingJob.enqueue(
                idFactory.nextJobId(), ProcessingJobTarget.forUpload(session.id()),
                ProcessingJobStage.VALIDATE_OWNERSHIP, "root", fingerprint, 0, clock.instant());
        // The store must persist the pin and unique intake job in one database transaction.
        UploadSession persisted = sessionStore.pinAndEnqueue(
                session, job, admissionPolicy.processingLimit(session.ownerType()));
        return statusOf(persisted);
    }

    @Override
    public UploadSessionStatus status(CompleteUploadCommand command) {
        return statusOf(requiredSession(Objects.requireNonNull(command, "command")));
    }

    private UploadSession requiredSession(CompleteUploadCommand request) {
        return sessionStore.findByIdForOwner(
                        requireText(request.uploadId(), "uploadId"),
                        Objects.requireNonNull(request.ownerType(), "ownerType"),
                        requireText(request.ownerKey(), "ownerKey"))
                .orElseThrow(() -> new UploadAdmissionException(UploadErrorCode.UPLOAD_NOT_FOUND));
    }

    private UploadSessionStatus statusOf(UploadSession session) {
        return new UploadSessionStatus(
                session.id(), session.state(),
                session.pinnedObject() == null ? null : session.pinnedObject().versionId(),
                session.materialId(), session.versionId(), session.errorCode());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private UploadAdmissionException rejected(InitiateUploadCommand request, UploadErrorCode code) {
        recordUpload(request, code.name());
        return new UploadAdmissionException(code);
    }

    private void recordUpload(InitiateUploadCommand request, String result) {
        try {
            telemetry.record(request.ownerType(), result, request.byteSize(), request.declaredMediaType());
        } catch (RuntimeException ignored) {
            // Admission semantics never depend on metric delivery.
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
