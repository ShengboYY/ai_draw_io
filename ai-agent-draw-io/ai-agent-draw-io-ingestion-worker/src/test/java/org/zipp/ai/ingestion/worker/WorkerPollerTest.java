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
        assertEquals(Duration.ofSeconds(17),
                WorkerPoller.retryDelayForAttempt(1, Duration.ofSeconds(17)));
        assertNull(WorkerPoller.retryDelayForAttempt(4, Duration.ofSeconds(17)));
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
                        ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE,
                        ProcessingJobStage.ANALYZE_VISUALS,
                        ProcessingJobStage.BUILD_EVIDENCE_UNITS,
                        ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS),
                WorkerPoller.claimableStages(true, true));
        assertEquals(java.util.Set.of(ProcessingJobStage.VALIDATE_OWNERSHIP,
                        ProcessingJobStage.RESOLVE_CONTENT_DEDUP, ProcessingJobStage.PROMOTE_ORIGINAL,
                        ProcessingJobStage.EXTRACT_NATIVE, ProcessingJobStage.OCR_SELECTED_PAGES,
                        ProcessingJobStage.NORMALIZE_CANONICAL_PAGES,
                        ProcessingJobStage.BUILD_DOCUMENT_STRUCTURE,
                        ProcessingJobStage.ANALYZE_VISUALS,
                        ProcessingJobStage.BUILD_EVIDENCE_UNITS,
                        ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS,
                        ProcessingJobStage.BUILD_LEXICAL_PROJECTION,
                        ProcessingJobStage.EMBED_CHUNK_BATCHES,
                        ProcessingJobStage.UPSERT_VECTOR_BATCHES,
                        ProcessingJobStage.VERIFY_PROJECTION_MANIFEST,
                        ProcessingJobStage.PUBLISH_REVISION,
                        ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION,
                        ProcessingJobStage.REPAIR_VECTOR_BATCH),
                WorkerPoller.claimableStages(true, true, true));
    }
}
