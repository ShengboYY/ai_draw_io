package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadSessionTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void repeatedCompletionKeepsTheFirstPinnedObjectVersion() {
        UploadSession session = UploadSession.create(
                "upl_1", OwnerType.USER, "usr_1", "idem_1", "guide.pdf",
                "application/pdf", 128L, "a".repeat(64),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/opaque", NOW.plusSeconds(600), NOW);
        QuarantineObjectVersion first = new QuarantineObjectVersion(
                "version-1", "etag-1", "checksum-1", 128L);
        QuarantineObjectVersion replay = new QuarantineObjectVersion(
                "version-2", "etag-2", "checksum-2", 128L);

        assertTrue(session.pinObject(first, NOW.plusSeconds(10)));
        assertFalse(session.pinObject(replay, NOW.plusSeconds(20)));

        assertEquals(UploadSessionState.OBJECT_VERSION_PINNED, session.state());
        assertEquals(first, session.pinnedObject());
        assertEquals(1L, session.generation());
    }

    @Test
    void onlyAWorkerCanAdvancePinnedUploadToATerminalState() {
        UploadSession session = newSession();
        session.pinObject(new QuarantineObjectVersion("version-1", "etag-1", null, 128L),
                NOW.plusSeconds(10));

        session.beginProcessing();
        session.reject(UploadErrorCode.REJECTED_SECURITY);

        assertEquals(UploadSessionState.REJECTED, session.state());
        assertEquals(UploadErrorCode.REJECTED_SECURITY.name(), session.errorCode());
        assertFalse(session.beginProcessing());
    }

    @Test
    void cancellationOnlyAppliesBeforeWorkerProcessingStarts() {
        UploadSession session = newSession();

        assertTrue(session.cancel());
        assertFalse(session.cancel());
        assertEquals(UploadSessionState.CANCELLED, session.state());
    }

    private static UploadSession newSession() {
        return UploadSession.create(
                "upl_1", OwnerType.USER, "usr_1", "idem_1", "guide.pdf",
                "application/pdf", 128L, "a".repeat(64),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/opaque", NOW.plusSeconds(600), NOW);
    }
}
