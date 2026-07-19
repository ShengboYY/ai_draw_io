package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.exception.UploadAdmissionException;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadQuotaSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadRateReservation;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IUploadSessionMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.UploadSessionPO;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Repository
public class MySqlUploadSessionStore implements UploadSessionStore {

    private static final String OWNER_RATE = "OWNER";
    private static final String IP_RATE = "IP";
    private static final String CONCURRENCY_LOCK = "CONCURRENCY";
    private static final String ADMISSION_LOCK = "ADMISSION";
    private static final Instant CONCURRENCY_BUCKET = Instant.EPOCH;
    private static final Instant CONCURRENCY_LOCK_EXPIRY = Instant.parse("2038-01-01T00:00:00Z");

    private final IUploadSessionMapper uploadMapper;
    private final IProcessingJobMapper jobMapper;

    public MySqlUploadSessionStore(IUploadSessionMapper uploadMapper, IProcessingJobMapper jobMapper) {
        this.uploadMapper = Objects.requireNonNull(uploadMapper, "uploadMapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    @Override
    public Optional<UploadSession> findByOwnerAndIdempotencyKey(OwnerType ownerType, String ownerKey,
                                                                String idempotencyKey) {
        return Optional.ofNullable(uploadMapper.selectByOwnerAndIdempotencyKey(
                ownerType.name(), ownerKey, idempotencyKey)).map(MySqlUploadSessionStore::toDomain);
    }

    @Override
    public Optional<UploadSession> findByIdForOwner(String uploadId, OwnerType ownerType, String ownerKey) {
        return Optional.ofNullable(uploadMapper.selectByIdForOwner(uploadId, ownerType.name(), ownerKey))
                .map(MySqlUploadSessionStore::toDomain);
    }

    @Override
    public UploadQuotaSnapshot quotaSnapshot(OwnerType ownerType, String ownerKey, String ipRateKey,
                                              Instant hourBucket) {
        String type = ownerType.name();
        return new UploadQuotaSnapshot(
                uploadMapper.sumAccountOriginalBytes(type, ownerKey),
                uploadMapper.countActiveFiles(type, ownerKey),
                uploadMapper.countProcessing(type, ownerKey),
                uploadMapper.rateBucketCount(OWNER_RATE, ownerKey, hourBucket),
                uploadMapper.rateBucketCount(IP_RATE, ipRateKey, hourBucket));
    }

    @Override
    @Transactional
    public UploadSession createAndConsumeRate(UploadSession session, String ipRateKey, Instant hourBucket,
                                              UploadRateReservation reservation) {
        UploadSession candidate = Objects.requireNonNull(session, "session");
        uploadMapper.ensureRateBucket(ADMISSION_LOCK, candidate.ownerKey(), CONCURRENCY_BUCKET,
                CONCURRENCY_LOCK_EXPIRY);
        uploadMapper.selectRateBucketForUpdate(ADMISSION_LOCK, candidate.ownerKey(), CONCURRENCY_BUCKET);
        Optional<UploadSession> replay = findByOwnerAndIdempotencyKey(
                candidate.ownerType(), candidate.ownerKey(), candidate.idempotencyKey());
        if (replay.isPresent()) {
            return replay.get();
        }
        String ownerType = candidate.ownerType().name();
        if (uploadMapper.countActiveFiles(ownerType, candidate.ownerKey()) >= reservation.activeFileLimit()) {
            throw new UploadAdmissionException(UploadErrorCode.WORKSPACE_FILE_LIMIT);
        }
        long reservedBytes = uploadMapper.sumAccountOriginalBytes(ownerType, candidate.ownerKey());
        if (reservedBytes > reservation.accountByteLimit() - candidate.expectedSize()) {
            throw new UploadAdmissionException(UploadErrorCode.ACCOUNT_STORAGE_LIMIT);
        }
        if (uploadMapper.insert(toPo(candidate)) == 0) {
            return findByOwnerAndIdempotencyKey(candidate.ownerType(), candidate.ownerKey(),
                    candidate.idempotencyKey()).orElseThrow(
                    () -> new IllegalStateException("idempotency winner was not readable"));
        }
        reserveRate(OWNER_RATE, candidate.ownerKey(), hourBucket, reservation.ownerHourlyLimit(),
                UploadErrorCode.WORKSPACE_RATE_LIMIT);
        reserveRate(IP_RATE, ipRateKey, hourBucket, reservation.ipHourlyLimit(), UploadErrorCode.IP_RATE_LIMIT);
        return candidate;
    }

    @Override
    @Transactional
    public UploadSession pinAndEnqueue(UploadSession session, ProcessingJob job, int processingLimit) {
        UploadSession candidate = Objects.requireNonNull(session, "session");
        if (processingLimit < 1) {
            throw new IllegalArgumentException("processingLimit must be positive");
        }
        // Serialize the count-and-pin decision per owner; a plain COUNT query would allow two
        // concurrent completes to exceed the processing limit under snapshot isolation.
        uploadMapper.ensureRateBucket(CONCURRENCY_LOCK, candidate.ownerKey(), CONCURRENCY_BUCKET,
                CONCURRENCY_LOCK_EXPIRY);
        uploadMapper.selectRateBucketForUpdate(CONCURRENCY_LOCK, candidate.ownerKey(), CONCURRENCY_BUCKET);
        UploadSession current = findByIdForOwner(candidate.id(), candidate.ownerType(), candidate.ownerKey())
                .orElseThrow(() -> new IllegalStateException("upload session disappeared before pin"));
        if (current.state() != UploadSessionState.CREATED) {
            return current;
        }
        if (uploadMapper.countProcessing(candidate.ownerType().name(), candidate.ownerKey()) >= processingLimit) {
            throw new UploadAdmissionException(UploadErrorCode.PROCESSING_CONCURRENCY_LIMIT);
        }
        UploadSessionPO po = toPo(candidate);
        int updated = uploadMapper.pinObject(po, candidate.generation() - 1);
        if (updated == 1) {
            jobMapper.insert(toPo(Objects.requireNonNull(job, "job")));
        }
        // Always return the database winner: concurrent complete calls can pin different latest versions.
        return findByIdForOwner(candidate.id(), candidate.ownerType(), candidate.ownerKey())
                .orElseThrow(() -> new IllegalStateException("pinned upload session disappeared"));
    }

    @Override
    public UploadSession expire(UploadSession session) {
        UploadSession candidate = Objects.requireNonNull(session, "session");
        uploadMapper.expire(candidate.id(), candidate.generation() - 1);
        return findByIdForOwner(candidate.id(), candidate.ownerType(), candidate.ownerKey())
                .orElseThrow(() -> new IllegalStateException("expired upload session disappeared"));
    }

    private void reserveRate(String subjectType, String subjectKey, Instant hourBucket, int limit,
                             UploadErrorCode rejection) {
        Instant expiresAt = hourBucket.plus(48, ChronoUnit.HOURS);
        uploadMapper.ensureRateBucket(subjectType, subjectKey, hourBucket, expiresAt);
        Integer current = uploadMapper.selectRateBucketForUpdate(subjectType, subjectKey, hourBucket);
        if (current == null || current >= limit) {
            throw new UploadAdmissionException(rejection);
        }
        if (uploadMapper.incrementRateBucket(subjectType, subjectKey, hourBucket) != 1) {
            throw new IllegalStateException("upload rate reservation was lost");
        }
    }

    private static UploadSessionPO toPo(UploadSession session) {
        UploadSessionPO po = new UploadSessionPO();
        po.setId(session.id());
        po.setOwnerType(session.ownerType().name());
        po.setOwnerKey(session.ownerKey());
        po.setIdempotencyKey(session.idempotencyKey());
        po.setDisplayName(session.displayName());
        po.setTargetScopeType(session.target().scopeType().name());
        po.setTargetScopeKey(session.target().scopeKey());
        po.setTargetRetentionClass(session.target().retentionClass().name());
        po.setNewVersionOfMaterialId(session.newVersionOfMaterialId());
        po.setExpectedSize(session.expectedSize());
        po.setExpectedSha256(session.expectedSha256());
        po.setDeclaredMime(session.declaredMediaType());
        po.setQuarantineBucket(session.quarantineBucket());
        po.setQuarantineKey(session.quarantineKey());
        if (session.pinnedObject() != null) {
            po.setQuarantineObjectVersionId(session.pinnedObject().versionId());
            po.setS3Etag(session.pinnedObject().eTag());
            po.setS3ChecksumSha256(session.pinnedObject().checksumSha256());
        }
        po.setPolicyExpiresAt(session.policyExpiresAt());
        po.setState(session.state().name());
        po.setMaterialId(session.materialId());
        po.setVersionId(session.versionId());
        po.setGeneration(session.generation());
        po.setErrorCode(session.errorCode());
        po.setCreatedAt(session.createdAt());
        return po;
    }

    private static ProcessingJobPO toPo(ProcessingJob job) {
        ProcessingJobPO po = new ProcessingJobPO();
        po.setId(job.id());
        po.setUploadSessionId(job.target().uploadSessionId());
        po.setRevisionId(job.target().revisionId());
        po.setStage(job.stage().name());
        po.setWorkKey(job.workKey());
        po.setInputFingerprint(job.inputFingerprint());
        po.setPriority(job.priority());
        po.setStatus(job.status().name());
        po.setAttempt(job.attempt());
        po.setNotBefore(job.notBefore());
        po.setFenceToken(job.fenceToken());
        return po;
    }

    static UploadSession toDomain(UploadSessionPO po) {
        QuarantineObjectVersion pinned = po.getQuarantineObjectVersionId() == null ? null
                : new QuarantineObjectVersion(po.getQuarantineObjectVersionId(), po.getS3Etag(),
                po.getS3ChecksumSha256(), po.getExpectedSize());
        return UploadSession.rehydrate(new UploadSessionSnapshot(
                po.getId(), OwnerType.valueOf(po.getOwnerType()), po.getOwnerKey(), po.getIdempotencyKey(),
                po.getDisplayName(), po.getDeclaredMime(), po.getExpectedSize(), po.getExpectedSha256(),
                new UploadTarget(MaterialScopeType.valueOf(po.getTargetScopeType()), po.getTargetScopeKey(),
                        RetentionClass.valueOf(po.getTargetRetentionClass())),
                po.getNewVersionOfMaterialId(), po.getQuarantineBucket(), po.getQuarantineKey(),
                po.getPolicyExpiresAt(), po.getCreatedAt(), UploadSessionState.valueOf(po.getState()), pinned,
                po.getGeneration(), po.getMaterialId(), po.getVersionId(), po.getErrorCode(),
                "VALIDATED".equals(po.getSecurityStatus())));
    }
}
