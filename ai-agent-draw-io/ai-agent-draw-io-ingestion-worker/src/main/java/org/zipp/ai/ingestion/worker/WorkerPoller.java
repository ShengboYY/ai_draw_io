package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;

public final class WorkerPoller {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration SECOND_RETRY_DELAY = Duration.ofSeconds(60);
    private static final Duration THIRD_RETRY_DELAY = Duration.ofMinutes(5);

    private final ProcessingQueuePort queue;
    private final SecureUploadJobHandler handler;
    private final Clock clock;
    private final String workerId;
    private final AtomicBoolean polling = new AtomicBoolean();

    public WorkerPoller(ProcessingQueuePort queue, SecureUploadJobHandler handler, Clock clock, String workerId) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        this.workerId = workerId.trim();
    }

    @Scheduled(fixedDelayString = "${worker.poll-delay-ms:1000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            queue.claim(workerId, clock.instant(), LEASE_DURATION,
                    Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP)).ifPresent(this::execute);
        } finally {
            polling.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${worker.reaper-delay-ms:60000}")
    public void requeueExpiredLeases() {
        queue.requeueExpiredLeases(clock.instant(), 100);
    }

    private void execute(ProcessingJobLease lease) {
        JobOutcome outcome = handler.handle(lease);
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
}
