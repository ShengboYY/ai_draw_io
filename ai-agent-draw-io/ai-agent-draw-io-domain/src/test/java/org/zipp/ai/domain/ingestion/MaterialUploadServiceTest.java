package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.BrowserPostPolicy;
import org.zipp.ai.domain.ingestion.model.valobj.CompleteUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.model.valobj.UploadQuotaSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadRateReservation;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import org.zipp.ai.domain.ingestion.port.UploadPolicySignerPort;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.ingestion.service.DefaultMaterialUploadService;
import org.zipp.ai.domain.ingestion.service.UploadAdmissionPolicy;
import org.zipp.ai.domain.ingestion.service.UploadIdFactory;
import org.zipp.ai.domain.ingestion.model.valobj.UploadLimits;
import org.zipp.ai.domain.ingestion.exception.UploadAdmissionException;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void initAndCompleteAreIdempotentAndPinOnlyOneObjectVersion() {
        InMemoryUploadStore store = new InMemoryUploadStore();
        FakeQuarantineObjectPort quarantine = new FakeQuarantineObjectPort();
        DefaultMaterialUploadService service = new DefaultMaterialUploadService(
                store, quarantine, session -> new BrowserPostPolicy(
                        "https://quarantine.s3.amazonaws.com", Map.of("key", session.quarantineKey()),
                        session.policyExpiresAt()),
                (ownerType, ownerKey, target, contextDiagramId, newVersionOfMaterialId) -> true,
                new UploadAdmissionPolicy(UploadLimits.defaults(true)),
                new SequentialUploadIdFactory(), Clock.fixed(NOW, ZoneOffset.UTC), "quarantine");
        InitiateUploadCommand command = new InitiateUploadCommand(
                OwnerType.USER, "usr_1", "idem_1", "guide.pdf", "application/pdf",
                128L, "a".repeat(64),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                "diagram_1", null, "ip-hour", 1);

        var firstInit = service.initiate(command);
        var repeatedInit = service.initiate(command);
        assertEquals(firstInit.uploadId(), repeatedInit.uploadId());
        assertEquals(1, store.sessions.size());

        quarantine.latest = new QuarantineObjectVersion("version-1", "etag-1", null, 128L);
        var firstComplete = service.complete(new CompleteUploadCommand(OwnerType.USER, "usr_1", firstInit.uploadId()));
        quarantine.latest = new QuarantineObjectVersion("version-2", "etag-2", null, 128L);
        var repeatedComplete = service.complete(new CompleteUploadCommand(OwnerType.USER, "usr_1", firstInit.uploadId()));

        assertEquals("version-1", firstComplete.pinnedObjectVersionId());
        assertEquals(firstComplete, repeatedComplete);
        assertEquals(1, store.jobs.size());
    }

    @Test
    void expiredCompletionPersistsTheExpiredState() {
        InMemoryUploadStore store = new InMemoryUploadStore();
        FakeQuarantineObjectPort quarantine = new FakeQuarantineObjectPort();
        MutableClock clock = new MutableClock(NOW);
        DefaultMaterialUploadService service = new DefaultMaterialUploadService(
                store, quarantine, session -> new BrowserPostPolicy("https://s3.example", Map.of(),
                session.policyExpiresAt()), (type, owner, target, diagram, version) -> true,
                new UploadAdmissionPolicy(UploadLimits.defaults(true)), new SequentialUploadIdFactory(),
                clock, "quarantine");
        var initiated = service.initiate(new InitiateUploadCommand(
                OwnerType.USER, "usr_1", "idem_expired", "guide.pdf", "application/pdf", 128L,
                "a".repeat(64), new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1",
                RetentionClass.TEMPORARY), "diagram_1", null, "ip-hour", 1));
        quarantine.latest = new QuarantineObjectVersion("version-1", "etag-1", null, 128L);
        clock.instant = NOW.plusSeconds(600);

        assertThrows(UploadAdmissionException.class, () -> service.complete(
                new CompleteUploadCommand(OwnerType.USER, "usr_1", initiated.uploadId())));
        assertEquals(UploadSessionState.EXPIRED, service.status(
                new CompleteUploadCommand(OwnerType.USER, "usr_1", initiated.uploadId())).state());
    }

    private static final class InMemoryUploadStore implements UploadSessionStore {
        private final Map<String, UploadSession> sessions = new LinkedHashMap<>();
        private final Map<String, ProcessingJob> jobs = new LinkedHashMap<>();

        @Override
        public Optional<UploadSession> findByOwnerAndIdempotencyKey(OwnerType ownerType, String ownerKey,
                                                                    String idempotencyKey) {
            return sessions.values().stream()
                    .filter(session -> session.ownerType() == ownerType
                            && session.ownerKey().equals(ownerKey)
                            && session.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<UploadSession> findByIdForOwner(String uploadId, OwnerType ownerType, String ownerKey) {
            return Optional.ofNullable(sessions.get(uploadId))
                    .filter(session -> session.ownerType() == ownerType && session.ownerKey().equals(ownerKey));
        }

        @Override
        public UploadQuotaSnapshot quotaSnapshot(OwnerType ownerType, String ownerKey, String ipRateKey,
                                                  Instant hourBucket) {
            return UploadQuotaSnapshot.empty();
        }

        @Override
        public UploadSession createAndConsumeRate(UploadSession session, String ipRateKey, Instant hourBucket,
                                                  UploadRateReservation reservation) {
            return sessions.computeIfAbsent(session.id(), ignored -> session);
        }

        @Override
        public UploadSession pinAndEnqueue(UploadSession session, ProcessingJob job, int processingLimit) {
            sessions.put(session.id(), session);
            jobs.putIfAbsent(job.id(), job);
            return session;
        }

        @Override
        public UploadSession expire(UploadSession session) {
            sessions.put(session.id(), session);
            return session;
        }
    }

    private static final class FakeQuarantineObjectPort implements QuarantineObjectPort {
        private QuarantineObjectVersion latest;

        @Override
        public Optional<QuarantineObjectVersion> headLatestVersion(String bucket, String objectKey) {
            return Optional.ofNullable(latest);
        }
    }

    private static final class SequentialUploadIdFactory implements UploadIdFactory {
        @Override public String nextUploadId() { return "upl_1"; }
        @Override public String nextObjectId() { return "obj_1"; }
        @Override public String nextJobId() { return "job_1"; }
        @Override public String ownerPathToken(String ownerKey) { return "owner-token"; }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
