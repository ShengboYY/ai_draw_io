package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;

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

    @Test
    void materializationStagesCanBeRolledBackWithoutDisablingSecureValidation() {
        assertEquals(java.util.Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP),
                WorkerPoller.claimableStages(false));
        assertEquals(java.util.Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP,
                        ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL),
                WorkerPoller.claimableStages(true));
        assertEquals(java.util.Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP,
                        ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL,
                        ProcessingJobStage.EXTRACT_NATIVE, ProcessingJobStage.OCR_SELECTED_PAGES,
                        ProcessingJobStage.NORMALIZE_CANONICAL_PAGES,
                        ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE),
                WorkerPoller.claimableStages(true, true));
    }
}
