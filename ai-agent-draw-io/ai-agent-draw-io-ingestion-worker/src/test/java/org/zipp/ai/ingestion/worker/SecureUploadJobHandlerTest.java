package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.model.valobj.ScanResult;
import org.zipp.ai.domain.ingestion.model.valobj.SecurityValidationResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.SecureUploadWorkPort;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.ingestion.worker.fake.FakeProcessingQueue;
import org.zipp.ai.ingestion.worker.security.SecureFileValidator;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SecureUploadJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void eicarNeverCrossesTheSecurityValidationBoundary() {
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        UploadSession session = UploadSession.create("upl_1", OwnerType.USER, "usr_1", "idem_1", "eicar.pdf",
                "application/pdf", eicar.length, sha256(eicar),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/opaque", NOW.plusSeconds(600), NOW);
        session.pinObject(new QuarantineObjectVersion("s3-version-1", "etag", null, eicar.length), NOW);
        FakeSecureUploadWork uploads = new FakeSecureUploadWork(session);
        FakeProcessingQueue queue = new FakeProcessingQueue();
        queue.enqueue(ProcessingJob.enqueue("job_1", ProcessingJobTarget.forUpload("upl_1"),
                ProcessingJobStage.VALIDATE_OWNERSHIP, "root", "f".repeat(64), 0, NOW));
        var lease = queue.claim("worker-1", NOW, Duration.ofMinutes(5)).orElseThrow();
        SecureUploadJobHandler handler = new SecureUploadJobHandler(uploads,
                (bucket, key, version, maximum, destination) -> {
                    try {
                        java.nio.file.Files.write(destination, eicar);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return new DownloadedQuarantineObject(key, destination, eicar.length, sha256(eicar));
                }, queue,
                new SecureFileValidator(ignored -> new ScanResult(false, "EICAR")),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.PERMANENT_FAILURE, outcome.kind());
        assertEquals(UploadSessionState.REJECTED, session.state());
        assertFalse(uploads.securityCommitted);
    }

    @Test
    void rejectedSessionReplayConvergesWithoutReadingQuarantineAgain() {
        byte[] content = "rejected".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UploadSession session = UploadSession.create("upl_2", OwnerType.USER, "usr_1", "idem_2", "bad.pdf",
                "application/pdf", content.length, sha256(content),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/opaque-2", NOW.plusSeconds(600), NOW);
        session.pinObject(new QuarantineObjectVersion("s3-version-2", "etag", null, content.length), NOW);
        session.beginProcessing();
        session.reject(UploadErrorCode.REJECTED_SECURITY);
        FakeSecureUploadWork uploads = new FakeSecureUploadWork(session);
        FakeProcessingQueue queue = new FakeProcessingQueue();
        queue.enqueue(ProcessingJob.enqueue("job_2", ProcessingJobTarget.forUpload("upl_2"),
                ProcessingJobStage.VALIDATE_OWNERSHIP, "root", "a".repeat(64), 0, NOW));
        var lease = queue.claim("worker-1", NOW, Duration.ofMinutes(5)).orElseThrow();
        SecureUploadJobHandler handler = new SecureUploadJobHandler(uploads,
                (bucket, key, version, maximum, destination) -> {
                    throw new AssertionError("terminal replay must not read quarantine");
                }, queue, new SecureFileValidator(ignored -> new ScanResult(true, null)),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        JobOutcome outcome = handler.handle(lease);

        assertEquals(JobOutcome.Kind.PERMANENT_FAILURE, outcome.kind());
        assertEquals(UploadErrorCode.REJECTED_SECURITY.name(), outcome.errorCode());
    }

    private static final class FakeSecureUploadWork implements SecureUploadWorkPort {
        private final UploadSession session;
        private boolean securityCommitted;

        private FakeSecureUploadWork(UploadSession session) { this.session = session; }
        @Override public Optional<UploadSession> findById(String uploadId) { return Optional.of(session); }
        @Override public Optional<UploadSession> beginProcessing(String uploadId, long generation, WorkerFence fence) {
            session.beginProcessing();
            return Optional.of(session);
        }
        @Override public boolean reject(String uploadId, long generation, UploadErrorCode reason, WorkerFence fence) {
            return session.reject(reason);
        }
        @Override public boolean commitSecurityValidation(String uploadId, long generation,
                                                          SecurityValidationResult result, ProcessingJob nextJob,
                                                          WorkerFence fence) {
            securityCommitted = true;
            return true;
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
