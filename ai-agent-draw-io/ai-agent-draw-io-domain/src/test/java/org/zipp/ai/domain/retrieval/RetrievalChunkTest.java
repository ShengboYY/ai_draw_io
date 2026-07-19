package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.model.aggregate.RetrievalChunk;
import org.zipp.ai.domain.retrieval.model.entity.EvidenceUnit;
import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;
import org.zipp.ai.domain.retrieval.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.EvidenceSourceChannel;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrievalChunkTest {

    @Test
    void citableChunkRequiresPrimaryEvidenceFromTheSameVersionAndRevision() {
        RetrievalChunk chunk = RetrievalChunk.create(
                "rc_1", "usr_1", "ver_1", "rev_1",
                RetrievalChunkType.CONTENT, EvidenceModality.TEXT, true,
                RetrievalIndexMode.DENSE_AND_LEXICAL);

        assertThrows(IllegalStateException.class, chunk::activate);

        chunk.attachEvidence(evidence("ev_1", "ver_1", "rev_1"), ChunkEvidenceRole.PRIMARY, 0);
        assertDoesNotThrow(chunk::activate);

        RetrievalChunk other = RetrievalChunk.create(
                "rc_2", "usr_1", "ver_1", "rev_1",
                RetrievalChunkType.CONTENT, EvidenceModality.TEXT, true,
                RetrievalIndexMode.DENSE_AND_LEXICAL);
        assertThrows(IllegalArgumentException.class,
                () -> other.attachEvidence(evidence("ev_2", "ver_2", "rev_1"), ChunkEvidenceRole.PRIMARY, 0));
    }

    @Test
    void bridgeChunksCanLocateEvidenceButCanNeverBecomeCitable() {
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalChunk.create(
                        "rc_bridge", "usr_1", "ver_1", "rev_1",
                        RetrievalChunkType.SECTION_BRIDGE, EvidenceModality.TEXT, true,
                        RetrievalIndexMode.DENSE_AND_LEXICAL));
    }

    private EvidenceUnit evidence(String id, String versionId, String revisionId) {
        return EvidenceUnit.create(
                id, "usr_1", versionId, revisionId, "page_1",
                EvidenceModality.TEXT, EvidenceSourceChannel.NATIVE,
                "objects/" + id + ".json.gz", null);
    }
}
