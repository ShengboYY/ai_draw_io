package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;
import org.zipp.ai.domain.retrieval.projection.LexicalProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionPlanner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorProjectionPlannerTest {

    @Test
    void plansOnlyDenseChunksIntoGenerationScopedBoundedBatches() {
        List<RetrievalChunkProjection> chunks = new ArrayList<>();
        List<LexicalProjection> lexical = new ArrayList<>();
        for (int index = 1; index <= 99; index++) {
            RetrievalIndexMode mode = index == 98 ? RetrievalIndexMode.LEXICAL_ONLY
                    : index == 99 ? RetrievalIndexMode.UNSEARCHABLE : RetrievalIndexMode.DENSE_AND_LEXICAL;
            String id = "chunk_" + index;
            chunks.add(chunk(id, index, mode));
            if (mode != RetrievalIndexMode.UNSEARCHABLE) {
                lexical.add(new LexicalProjection(id, "agile " + index, null, List.of()));
            }
        }
        RetrievalProjectionManifest manifest = new RetrievalProjectionManifest(
                "retrieval-projection-v1", "rev_1", "ver_1", "a".repeat(64), "builder-v1",
                chunks, lexical, "b".repeat(64));
        VectorGenerationProfile profile = new VectorGenerationProfile(
                "drawio-retrieval-v1", "prod", "multilingual-e5-large", "c".repeat(64),
                1024, "cosine", "vector-v1", "tokenizer-v1");

        var plan = new VectorProjectionPlanner(96, 1_000_000).plan(manifest, profile);

        assertEquals(97, plan.projections().size());
        assertEquals(2, plan.batches().size());
        assertEquals(96, plan.batches().get(0).projections().size());
        assertEquals(1, plan.batches().get(1).projections().size());
        assertEquals("ig:" + plan.generationId() + ":batch:0000", plan.batches().get(0).workKey());
        assertTrue(plan.projections().get(0).vectorId().startsWith("rc_chunk_1_ig"));
        assertNotEquals(plan.batches().get(0).inputFingerprint(),
                plan.batches().get(1).inputFingerprint());
        assertEquals(plan, new VectorProjectionPlanner(96, 1_000_000).plan(manifest, profile));
    }

    private RetrievalChunkProjection chunk(String id, int ordinal, RetrievalIndexMode mode) {
        String text = "Agile development flow " + ordinal;
        return new RetrievalChunkProjection(id, "page_1", "section_1", RetrievalChunkType.CONTENT,
                EvidenceModality.TEXT, "en", true, mode, text, sha(ordinal), null, List.of(),
                10, 0.9, ordinal, List.of(new org.zipp.ai.domain.retrieval.projection.RetrievalEvidenceMapping(
                "evidence_" + ordinal,
                org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole.PRIMARY, 0, null, null)));
    }

    private String sha(int value) {
        return String.format("%064x", value);
    }
}
