package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.MaterialIngestionTelemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkerPoller {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SECOND_RETRY_DELAY = Duration.ofSeconds(60);
    private static final Duration THIRD_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Duration STALLED_UNCLAIMED_THRESHOLD = Duration.ofMinutes(15);
    private static final int STALLED_RECOVERY_BATCH_SIZE = 100;

    private final ProcessingQueuePort queue;
    private final SecureUploadJobHandler secureUploadHandler;
    private final MaterializationJobHandler materializationHandler;
    private final DocumentProcessingJobHandler documentProcessingHandler;
    private final VectorProjectionJobHandler vectorProjectionHandler;
    private final Clock clock;
    private final String workerId;
    private final Set<ProcessingJobStage> claimableStages;
    private final Set<String> compatibleProcessingFingerprints;
    private final String projectionGenerationId;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final MaterialIngestionTelemetry telemetry;

    public WorkerPoller(ProcessingQueuePort queue,
                        SecureUploadJobHandler secureUploadHandler,
                        MaterializationJobHandler materializationHandler,
                        DocumentProcessingJobHandler documentProcessingHandler,
                        VectorProjectionJobHandler vectorProjectionHandler,
                        Clock clock, String workerId, boolean materializationEnabled,
                        boolean documentProcessingEnabled, boolean vectorProjectionEnabled,
                        String processingFingerprint, String projectionGenerationId) {
        this(queue, secureUploadHandler, materializationHandler, documentProcessingHandler,
                vectorProjectionHandler, clock, workerId, materializationEnabled,
                documentProcessingEnabled, vectorProjectionEnabled, processingFingerprint,
                projectionGenerationId, MaterialIngestionTelemetry.NOOP);
    }

    public WorkerPoller(ProcessingQueuePort queue,
                        SecureUploadJobHandler secureUploadHandler,
                        MaterializationJobHandler materializationHandler,
                        DocumentProcessingJobHandler documentProcessingHandler,
                        VectorProjectionJobHandler vectorProjectionHandler,
                        Clock clock, String workerId, boolean materializationEnabled,
                        boolean documentProcessingEnabled, boolean vectorProjectionEnabled,
                        String processingFingerprint, String projectionGenerationId,
                        MaterialIngestionTelemetry telemetry) {
        this(queue, secureUploadHandler, materializationHandler, documentProcessingHandler,
                vectorProjectionHandler, clock, workerId, materializationEnabled,
                documentProcessingEnabled, vectorProjectionEnabled,
                processingFingerprint == null ? Set.of() : Set.of(processingFingerprint),
                projectionGenerationId, telemetry);
    }

    public WorkerPoller(ProcessingQueuePort queue,
                        SecureUploadJobHandler secureUploadHandler,
                        MaterializationJobHandler materializationHandler,
                        DocumentProcessingJobHandler documentProcessingHandler,
                        VectorProjectionJobHandler vectorProjectionHandler,
                        Clock clock, String workerId, boolean materializationEnabled,
                        boolean documentProcessingEnabled, boolean vectorProjectionEnabled,
                        Set<String> compatibleProcessingFingerprints, String projectionGenerationId,
                        MaterialIngestionTelemetry telemetry) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.secureUploadHandler = Objects.requireNonNull(secureUploadHandler, "secureUploadHandler");
        this.materializationHandler = materializationEnabled
                ? Objects.requireNonNull(materializationHandler, "materializationHandler") : materializationHandler;
        this.documentProcessingHandler = documentProcessingEnabled
                ? Objects.requireNonNull(documentProcessingHandler, "documentProcessingHandler")
                : documentProcessingHandler;
        this.vectorProjectionHandler = vectorProjectionEnabled
                ? Objects.requireNonNull(vectorProjectionHandler, "vectorProjectionHandler")
                : vectorProjectionHandler;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        this.workerId = workerId.trim();
        if (documentProcessingEnabled && !materializationEnabled) {
            throw new IllegalArgumentException("document processing requires materialization");
        }
        if (vectorProjectionEnabled && !documentProcessingEnabled) {
            throw new IllegalArgumentException("vector projection requires document processing");
        }
        this.claimableStages = claimableStages(
                materializationEnabled, documentProcessingEnabled, vectorProjectionEnabled);
        if (compatibleProcessingFingerprints == null || compatibleProcessingFingerprints.isEmpty()
                || compatibleProcessingFingerprints.stream()
                .anyMatch(fingerprint -> fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "compatibleProcessingFingerprints must contain lowercase SHA-256 values");
        }
        this.compatibleProcessingFingerprints = Set.copyOf(compatibleProcessingFingerprints);
        this.projectionGenerationId = vectorProjectionEnabled
                ? requireText(projectionGenerationId, "projectionGenerationId") : null;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            queue.claim(workerId, clock.instant(), LEASE_DURATION, claimableStages,
                            compatibleProcessingFingerprints, projectionGenerationId)
                    .ifPresent(this::execute);
        } finally {
            polling.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${worker.reaper-delay-ms:60000}")
    public void requeueExpiredLeases() {
        queue.requeueExpiredLeases(clock.instant(), 100);
    }

    @Scheduled(fixedDelayString = "${worker.stalled-recovery-delay-ms:60000}")
    public void prioritizeStalledUnclaimed() {
        Instant now = clock.instant();
        // Only untouched compatible jobs are reprioritized; running and completed jobs remain immutable.
        queue.prioritizeStalledUnclaimed(now, now.minus(STALLED_UNCLAIMED_THRESHOLD),
                claimableStages, compatibleProcessingFingerprints, projectionGenerationId,
                STALLED_RECOVERY_BATCH_SIZE);
    }

    private void execute(ProcessingJobLease lease) {
        Instant startedAt = clock.instant();
        ProcessingJobStage stage = lease.job().stage();
        JobOutcome outcome;
        try {
            outcome = switch (stage) {
            case VALIDATE_OWNERSHIP -> secureUploadHandler.handle(lease);
            case RESOLVE_CONTENT_DEDUP, PROMOTE_ORIGINAL -> requireMaterializationHandler().handle(lease);
            case EXTRACT_NATIVE, OCR_SELECTED_PAGES, NORMALIZE_CANONICAL_PAGES, BUILD_DOCUMENT_STRUCTURE,
                    ANALYZE_VISUALS, BUILD_EVIDENCE_UNITS, BUILD_RETRIEVAL_CHUNKS ->
                    requireDocumentProcessingHandler().handle(lease);
            case BUILD_LEXICAL_PROJECTION, EMBED_CHUNK_BATCHES, UPSERT_VECTOR_BATCHES,
                    VERIFY_PROJECTION_MANIFEST, PUBLISH_REVISION, BUILD_COMPATIBILITY_PROJECTION,
                    REPAIR_VECTOR_BATCH ->
                    requireVectorProjectionHandler().handle(lease);
            default -> JobOutcome.permanent("UNSUPPORTED_WORKER_STAGE");
            };
        } catch (RuntimeException exception) {
            recordTelemetry(stage, "exception", startedAt);
            throw exception;
        }
        var job = lease.job();
        switch (outcome.kind()) {
            case SUCCEEDED -> queue.succeed(job.id(), workerId, lease.fenceToken());
            case PERMANENT_FAILURE -> queue.fail(job.id(), workerId, lease.fenceToken(), outcome.errorCode());
            case TRANSIENT_FAILURE -> {
                Duration retryDelay = retryDelayForAttempt(job.attempt(), outcome.retryAfter());
                if (retryDelay == null) {
                    queue.fail(job.id(), workerId, lease.fenceToken(), outcome.errorCode());
                } else {
                    queue.retry(job.id(), workerId, lease.fenceToken(), outcome.errorCode(),
                            clock.instant().plus(retryDelay));
                }
            }
        }
        recordTelemetry(stage, outcome.kind().name(), startedAt);
    }

    private void recordTelemetry(ProcessingJobStage stage, String result, Instant startedAt) {
        try {
            telemetry.record(stage, result, Duration.between(startedAt, clock.instant()));
        } catch (RuntimeException ignored) {
            // Metric delivery cannot change queue acknowledgement or retry semantics.
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

    static Duration retryDelayForAttempt(int attempt, Duration providerDelay) {
        Duration fallback = retryDelayForAttempt(attempt);
        if (fallback == null) return null;
        return providerDelay == null ? fallback : providerDelay;
    }

    static Set<ProcessingJobStage> claimableStages(boolean materializationEnabled) {
        return claimableStages(materializationEnabled, false, false);
    }

    static Set<ProcessingJobStage> claimableStages(boolean materializationEnabled,
                                                   boolean documentProcessingEnabled) {
        return claimableStages(materializationEnabled, documentProcessingEnabled, false);
    }

    static Set<ProcessingJobStage> claimableStages(boolean materializationEnabled,
                                                   boolean documentProcessingEnabled,
                                                   boolean vectorProjectionEnabled) {
        if (!materializationEnabled) {
            return Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP);
        }
        if (documentProcessingEnabled) {
            Set<ProcessingJobStage> stages = new java.util.HashSet<>(Set.of(
                    ProcessingJobStage.VALIDATE_OWNERSHIP,
                    ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL,
                    ProcessingJobStage.EXTRACT_NATIVE, ProcessingJobStage.OCR_SELECTED_PAGES,
                    ProcessingJobStage.NORMALIZE_CANONICAL_PAGES, ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE,
                    ProcessingJobStage.ANALYZE_VISUALS, ProcessingJobStage.BUILD_EVIDENCE_UNITS,
                    ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS));
            if (vectorProjectionEnabled) {
                stages.addAll(Set.of(ProcessingJobStage.BUILD_LEXICAL_PROJECTION,
                        ProcessingJobStage.EMBED_CHUNK_BATCHES,
                        ProcessingJobStage.UPSERT_VECTOR_BATCHES,
                        ProcessingJobStage.VERIFY_PROJECTION_MANIFEST,
                        ProcessingJobStage.PUBLISH_REVISION,
                        ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION,
                        ProcessingJobStage.REPAIR_VECTOR_BATCH));
            }
            return Set.copyOf(stages);
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

    private VectorProjectionJobHandler requireVectorProjectionHandler() {
        if (vectorProjectionHandler == null) {
            throw new IllegalStateException("vector projection handler is disabled");
        }
        return vectorProjectionHandler;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
