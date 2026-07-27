package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.projection.LexicalProjection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchHybridRankerTest {

    @Test
    void lexicalRankingShouldPreferAnExactProjectedIdentifier() {
        List<LexicalProjection> projections = List.of(
                projection("generic", "The rollback threshold is documented here.", List.of()),
                projection("identifier", "Operational details for gateway GW-204.",
                        List.of(new LexicalProjection.ExactTerm("gw-204", "IDENTIFIER"))));

        assertEquals(List.of("identifier"),
                ResearchHybridRanker.lexicalRank("Find gateway GW-204", projections));
    }

    @Test
    void lexicalRankingShouldUseCjkBigrams() {
        List<LexicalProjection> projections = List.of(
                new LexicalProjection("unrelated", null, "审批完成后更新库存", List.of()),
                new LexicalProjection("matching", null, "故障恢复需要回滚检查", List.of()));

        assertEquals("matching", ResearchHybridRanker.lexicalRank("故障回滚流程", projections).get(0));
    }

    @Test
    void weightedRrfShouldMatchTheProductLaneWeights() {
        List<String> fused = ResearchHybridRanker.fuse(
                List.of("lexical-only", "shared"), List.of("dense-only", "shared"), 3);

        assertEquals(List.of("shared", "lexical-only", "dense-only"), fused);
    }

    private LexicalProjection projection(String chunkId, String text,
                                         List<LexicalProjection.ExactTerm> exactTerms) {
        return new LexicalProjection(chunkId, text, null, exactTerms);
    }
}
