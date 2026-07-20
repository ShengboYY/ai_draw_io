package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionState;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingStage;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessingRevisionTest {

    @Test
    void aRevisionPublishesOnlyAfterItsPipelineReachesPublishing() {
        ProcessingRevision revision = ProcessingRevision.start(
                "rev_1", "ver_1", 1, "sha256:processing", Set.of(5, 6));

        assertThrows(IllegalStateException.class,
                () -> revision.publishReady(Instant.parse("2026-07-19T00:00:00Z")));

        revision.advanceTo(ProcessingStage.EXTRACTING, 30);
        revision.advanceTo(ProcessingStage.OCR_VISUAL, 60);
        revision.advanceTo(ProcessingStage.INDEXING, 90);
        revision.advanceTo(ProcessingStage.PUBLISHING, 99);
        revision.publishPartial("gaps/rev_1.json", Instant.parse("2026-07-19T00:00:00Z"));

        assertEquals(ProcessingRevisionState.PARTIAL_READY, revision.state());
        assertEquals("gaps/rev_1.json", revision.gapManifestKey());
    }

    @Test
    void aFailedRevisionCannotBeRevivedByLateWorkerProgress() {
        ProcessingRevision revision = ProcessingRevision.start(
                "rev_1", "ver_1", 1, "sha256:processing", Set.of());
        revision.fail("FATAL_PROCESSING");

        assertThrows(IllegalStateException.class,
                () -> revision.advanceTo(ProcessingStage.EXTRACTING, 30));
    }

    @Test
    void anExplicitRetryReopensOnlyAFailedRevisionAtItsCheckpoint() {
        ProcessingRevision revision = ProcessingRevision.rehydrateFailed(
                "rev_1", "ver_1", 1, "sha256:processing", Set.of(),
                ProcessingStage.OCR_VISUAL, 60, "OCR_FAILED");

        revision.retryFailed();

        assertEquals(ProcessingRevisionState.PROCESSING, revision.state());
        assertEquals(ProcessingStage.OCR_VISUAL, revision.stage());
        assertEquals(60, revision.progress());
        assertThrows(IllegalStateException.class, revision::retryFailed);
    }

    @Test
    void aRevisionCannotSkipRequiredPipelineStages() {
        ProcessingRevision revision = ProcessingRevision.start(
                "rev_1", "ver_1", 1, "sha256:processing", Set.of());

        assertThrows(IllegalArgumentException.class,
                () -> revision.advanceTo(ProcessingStage.INDEXING, 90));
        assertEquals(ProcessingStage.CREATED, revision.stage());
    }
}
