package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkerPoller {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SECOND_RETRY_DELAY = Duration.ofSeconds(60);
    private static final Duration THIRD_RETRY_DELAY = Duration.ofMinutes(5);

    private final ProcessingQueuePort queue;
    private final SecureUploadJobHandler secureUploadHandler;
    private final MaterializationJobHandler materializationHandler;
    private final DocumentProcessingJobHandler documentProcessingHandler;
    private final Clock clock;
    private final String workerId;
    private final Set<ProcessingJobStage> claimableStages;
    private final String processingFingerprint;
    private final AtomicBoolean polling = new AtomicBoolean();

    public WorkerPoller(ProcessingQueuePort queue,
                        SecureUploadJobHandler secureUploadHandler,
                        MaterializationJobHandler materializationHandler,
                        DocumentProcessingJobHandler documentProcessingHandler,
                        Clock clock, String workerId, boolean materializationEnabled,
                        boolean documentProcessingEnabled, String processingFingerprint) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.secureUploadHandler = Objects.requireNonNull(secureUploadHandler, "secureUploadHandler");
        this.materializationHandler = materializationEnabled
                ? Objects.requireNonNull(materializationHandler, "materializationHandler") : materializationHandler;
        this.documentProcessingHandler = documentProcessingEnabled
                ? Objects.requireNonNull(documentProcessingHandler, "documentProcessingHandler")
                : documentProcessingHandler;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        this.workerId = workerId.trim();
        if (documentProcessingEnabled && !materializationEnabled) {
            throw new IllegalArgumentException("document processing requires materialization");
        }
        this.claimableStages = claimableStages(materializationEnabled, documentProcessingEnabled);
        if (processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("processingFingerprint must be lowercase SHA-256");
        }
        this.processingFingerprint = processingFingerprint;
    }

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            queue.claim(workerId, clock.instant(), LEASE_DURATION, claimableStages, processingFingerprint)
                    .ifPresent(this::execute);
        } finally {
            polling.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${worker.reaper-delay-ms:60000}")
    public void requeueExpiredLeases() {
        queue.requeueExpiredLeases(clock.instant(), 100);
    }

    private void execute(ProcessingJobLease lease) {
        JobOutcome outcome = switch (lease.job().stage()) {
            case VALIDATE_OWNERSHIP -> secureUploadHandler.handle(lease);
            case RESOLVE_CONTENT_DEDUP, PROMOTE_ORIGINAL -> requireMaterializationHandler().handle(lease);
            case EXTRACT_NATIVE, OCR_SELECTED_PAGES, NORMALIZE_CANONICAL_PAGES, BUILD_DOCUMENT_STRUCTURE,
                    ANALYZE_VISUALS, BUILD_EVIDENCE_UNITS ->
                    requireDocumentProcessingHandler().handle(lease);
            default -> JobOutcome.permanent("UNSUPPORTED_WORKER_STAGE");
        };
        var job = lease.job();
        switch (outcome.kind()) {
            case SUCCEEDED -> queue.succeed(job.id(), workerId, lease.fenceToken());
            case PERMANENT_FAILURE -> queue.fail(job.id(), workerId, lease.fenceToken(), outcome.errorCode());
            case TRANSIENT_FAILURE -> {
                Duration retryDelay = retryDelayForAttempt(job.attempt());
                if (retryDelay == null) {
                    queue.fail(job.id(), workerId, lease.fenceToken(), outcome.errorCode());
                } else {
                    queue.retry(job.id(), workerId, lease.fenceToken(), outcome.errorCode(),
                            clock.instant().plus(retryDelay));
                }
            }
        }
    }

    static Duration retryDelayForAttempt(int attempt) {
        // The claim increments attempt; three retries means the fourth failed execution is terminal.
        return switch (attempt) {
            case 1 -> FIRST_RETRY_DELAY;
            case 2 -> SECOND_RETRY_DELAY;
            case 3 -> THIRD_RETRY_DELAY;
            default -> null;
        };
    }

    static Set<ProcessingJobStage> claimableStages(boolean materializationEnabled) {
        return claimableStages(materializationEnabled, false);
    }

    static Set<ProcessingJobStage> claimableStages(boolean materializationEnabled,
                                                   boolean documentProcessingEnabled) {
        if (!materializationEnabled) {
            return Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP);
        }
        if (documentProcessingEnabled) {
            return Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP,
                    ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL,
                    ProcessingJobStage.EXTRACT_NATIVE, ProcessingJobStage.OCR_SELECTED_PAGES,
                    ProcessingJobStage.NORMALIZE_CANONICAL_PAGES, ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE,
                    ProcessingJobStage.ANALYZE_VISUALS, ProcessingJobStage.BUILD_EVIDENCE_UNITS);
        }
        return Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP,
                ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL);
    }

    private MaterializationJobHandler requireMaterializationHandler() {
        if (materializationHandler == null) {
            throw new IllegalStateException("materialization handler is disabled");
        }
        return materializationHandler;
    }

    private DocumentProcessingJobHandler requireDocumentProcessingHandler() {
        if (documentProcessingHandler == null) {
            throw new IllegalStateException("document processing handler is disabled");
        }
        return documentProcessingHandler;
    }
}
