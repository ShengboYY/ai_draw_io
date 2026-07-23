package org.zipp.ai.ingestion.worker.research;

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
                                  List<String> lexicalChunkIds, int limit) {
        if (originalVectorIds.isEmpty() && rewrittenVectorIds.isEmpty()) return List.of();
        List<String> denseUnionVectorIds = ResearchQueryRankFusion.fuse(
                originalVectorIds, rewrittenVectorIds,
                originalVectorIds.size() + rewrittenVectorIds.size());
        List<String> denseUnionChunkIds = denseUnionVectorIds.stream()
                .map(vectorId -> requiredChunkId(chunkIdByVectorId, vectorId)).toList();
        Set<String> union = new LinkedHashSet<>(denseUnionChunkIds);
        List<String> lexicalWithinUnion = lexicalChunkIds.stream().filter(union::contains).toList();
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
