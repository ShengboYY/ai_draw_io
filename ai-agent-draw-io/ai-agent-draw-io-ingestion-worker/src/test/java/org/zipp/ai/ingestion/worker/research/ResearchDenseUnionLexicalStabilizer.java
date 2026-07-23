package org.zipp.ai.ingestion.worker.research;

import org.zipp.ai.domain.retrieval.projection.LexicalProjection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministically reranks only the chunks returned by the two authorized dense lanes. */
final class ResearchDenseUnionLexicalStabilizer {
    static final String FINGERPRINT = "dense-union-lexical-stabilization-v1|"
            + ResearchQueryRankFusion.FINGERPRINT + "|" + ResearchHybridRanker.FINGERPRINT;

    private ResearchDenseUnionLexicalStabilizer() { }

    static List<String> stabilize(List<String> originalVectorIds, List<String> rewrittenVectorIds,
                                  Map<String, String> chunkIdByVectorId,
                                  String query, List<LexicalProjection> lexicalProjections,
                                  int limit) {
        if (originalVectorIds.isEmpty() && rewrittenVectorIds.isEmpty()) return List.of();
        List<String> denseUnionVectorIds = ResearchQueryRankFusion.fuse(
                originalVectorIds, rewrittenVectorIds,
                originalVectorIds.size() + rewrittenVectorIds.size());
        List<String> denseUnionChunkIds = denseUnionVectorIds.stream()
                .map(vectorId -> requiredChunkId(chunkIdByVectorId, vectorId)).toList();
        Set<String> union = new LinkedHashSet<>(denseUnionChunkIds);
        // Scope both lexical scores and IDF statistics to the authorized dense union.
        List<String> lexicalWithinUnion = ResearchHybridRanker.lexicalRank(query,
                lexicalProjections.stream().filter(projection ->
                        union.contains(projection.chunkId())).toList());
        List<String> stabilizedChunkIds = ResearchHybridRanker.fuse(
                lexicalWithinUnion, denseUnionChunkIds, limit);
        Map<String, String> vectorIdByChunkId = new LinkedHashMap<>();
        for (String vectorId : denseUnionVectorIds) {
            vectorIdByChunkId.putIfAbsent(requiredChunkId(chunkIdByVectorId, vectorId), vectorId);
        }
        return stabilizedChunkIds.stream().map(vectorIdByChunkId::get).toList();
    }

    private static String requiredChunkId(Map<String, String> chunkIdByVectorId, String vectorId) {
        String chunkId = chunkIdByVectorId.get(vectorId);
        if (chunkId == null) {
            throw new IllegalArgumentException("Dense union vector lacks a chunk mapping: " + vectorId);
        }
        return chunkId;
    }
}
