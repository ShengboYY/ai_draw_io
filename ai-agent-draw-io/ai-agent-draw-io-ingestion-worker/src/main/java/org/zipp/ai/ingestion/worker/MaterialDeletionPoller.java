package org.zipp.ai.ingestion.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.zipp.ai.domain.material.port.MaterialDeletionWorkPort;

import java.time.Clock;
import java.time.Duration;

/** Claims durable deletion work independently from document-ingestion feature flags. */
public final class MaterialDeletionPoller {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private final MaterialDeletionWorkPort work;
    private final MaterialDeletionTaskHandler handler;
    private final Clock clock;
    private final String workerId;

    public MaterialDeletionPoller(MaterialDeletionWorkPort work, MaterialDeletionTaskHandler handler,
                                  Clock clock, String workerId) {
        this.work = work;
        this.handler = handler;
        this.clock = clock;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${worker.deletion-poll-delay-ms:1000}")
    public void poll() {
        work.claim(workerId, clock.instant(), LEASE_DURATION).ifPresent(handler::handle);
    }

    @Scheduled(fixedDelayString = "${worker.deletion-reaper-delay-ms:60000}")
    public void requeueExpiredLeases() {
        work.requeueExpiredLeases(clock.instant(), 100);
    }
}
