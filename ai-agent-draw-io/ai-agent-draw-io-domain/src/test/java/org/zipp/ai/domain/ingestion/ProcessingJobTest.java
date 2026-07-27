package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStatus;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingJobTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void queueClaimUsesANewFenceSoLateWorkersCannotCommit() {
        ProcessingJob job = ProcessingJob.enqueue(
                "job_1", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.OCR_SELECTED_PAGES, "page:12", "sha256:input", 10, NOW);

        long firstFence = job.claim("worker-a", NOW, Duration.ofMinutes(2));
        assertTrue(job.retryExpiredLease(NOW.plus(Duration.ofMinutes(3))));
        long secondFence = job.claim("worker-b", NOW.plus(Duration.ofMinutes(3)), Duration.ofMinutes(2));

        assertEquals(firstFence + 1, secondFence);
        assertFalse(job.succeed("worker-a", firstFence));
        assertTrue(job.succeed("worker-b", secondFence));
        assertEquals(ProcessingJobStatus.SUCCEEDED, job.status());
        assertEquals(ProcessingJobStage.OCR_SELECTED_PAGES, job.stage());
    }

    @Test
    void jobTargetRequiresExactlyOneAggregate() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingJobTarget("upload_1", "rev_1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingJobTarget(null, null));
    }
}
