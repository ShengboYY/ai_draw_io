package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingStage;
import org.zipp.ai.domain.material.model.aggregate.MaterialVersion;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialVersionTest {

    @Test
    void failedRevisionNeverReplacesTheLastPublishedRevision() {
        MaterialVersion version = MaterialVersion.create(
                "ver_1", "mat_1", "usr_1", 1, "blob_1", "sha256:content", 120L);
        ProcessingRevision ready = readyRevision("rev_1", 1);
        version.publish(ready);

        ProcessingRevision failed = ProcessingRevision.start(
                "rev_2", "ver_1", 2, "sha256:new-processing", Set.of());
        failed.fail("FATAL_PROCESSING");

        assertThrows(IllegalArgumentException.class, () -> version.publish(failed));
        assertEquals("rev_1", version.activeRevisionId());
    }

    private ProcessingRevision readyRevision(String id, int revisionNo) {
        ProcessingRevision revision = ProcessingRevision.start(
                id, "ver_1", revisionNo, "sha256:processing-" + revisionNo, Set.of());
        revision.advanceTo(ProcessingStage.EXTRACTING, 30);
        revision.advanceTo(ProcessingStage.OCR_VISUAL, 60);
        revision.advanceTo(ProcessingStage.INDEXING, 90);
        revision.advanceTo(ProcessingStage.PUBLISHING, 99);
        revision.publishReady(Instant.parse("2026-07-19T00:00:00Z"));
        return revision;
    }
}
