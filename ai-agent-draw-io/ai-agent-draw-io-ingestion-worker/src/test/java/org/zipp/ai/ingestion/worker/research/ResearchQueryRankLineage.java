package org.zipp.ai.ingestion.worker.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialises provider rank lineage without consulting task assertions or evaluator data. */
final class ResearchQueryRankLineage {
    static final String FINGERPRINT = "original-rewrite-top80-fused-ranks-v1";

    private ResearchQueryRankLineage() { }

    static Map<String, Object> trace(List<String> original, List<String> rewritten,
                                     List<String> fused) {
        Map<String, Integer> originalRanks = ranks(original);
        Map<String, Integer> rewrittenRanks = ranks(rewritten);
        List<Map<String, Object>> fusedRanks = new ArrayList<>();
        for (int index = 0; index < fused.size(); index++) {
            String chunkId = fused.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("chunkId", chunkId);
            if (originalRanks.containsKey(chunkId)) {
                item.put("originalRank", originalRanks.get(chunkId));
            }
            if (rewrittenRanks.containsKey(chunkId)) {
                item.put("rewrittenRank", rewrittenRanks.get(chunkId));
            }
            fusedRanks.add(Map.copyOf(item));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalTop80ChunkIds", List.copyOf(original));
        result.put("rewrittenTop80ChunkIds", List.copyOf(rewritten));
        result.put("fusedTop40", List.copyOf(fusedRanks));
        return Map.copyOf(result);
    }

    private static Map<String, Integer> ranks(List<String> chunkIds) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < chunkIds.size(); index++) {
            ranks.putIfAbsent(chunkIds.get(index), index + 1);
        }
        return Map.copyOf(ranks);
    }
}
