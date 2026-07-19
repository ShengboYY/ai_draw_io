package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkerPollerTest {

    @Test
    void transientFailuresStopAfterThreeRetries() {
        assertEquals(Duration.ofSeconds(10), WorkerPoller.retryDelayForAttempt(1));
        assertEquals(Duration.ofSeconds(60), WorkerPoller.retryDelayForAttempt(2));
        assertEquals(Duration.ofMinutes(5), WorkerPoller.retryDelayForAttempt(3));
        assertNull(WorkerPoller.retryDelayForAttempt(4));
    }
}
