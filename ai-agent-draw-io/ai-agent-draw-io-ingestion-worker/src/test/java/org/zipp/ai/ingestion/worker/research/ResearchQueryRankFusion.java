package org.zipp.ai.ingestion.worker.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Deterministically fuses original and rewritten dense ranks without evaluator inputs. */
final class ResearchQueryRankFusion {
    static final String FINGERPRINT = "equal-rrf-v1:k60:original1.0:rewritten1.0";
    private static final int RRF_K = 60;

    private ResearchQueryRankFusion() { }

    static List<String> fuse(List<String> original, List<String> rewritten, int limit) {
        if (limit < 1) throw new IllegalArgumentException("fusion limit must be positive");
        Map<String, Score> scores = new HashMap<>();
        addLane(scores, original);
        addLane(scores, rewritten);
        return scores.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, Score>>comparingDouble(entry -> entry.getValue().value()).reversed()
                        .thenComparingInt(entry -> entry.getValue().bestRank())
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void addLane(Map<String, Score> scores, List<String> lane) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(lane));
        for (int index = 0; index < unique.size(); index++) {
            String vectorId = unique.get(index);
            int rank = index + 1;
            Score prior = scores.getOrDefault(vectorId, new Score(0.0, Integer.MAX_VALUE));
            scores.put(vectorId, new Score(
                    prior.value() + 1.0 / (RRF_K + rank),
                    Math.min(prior.bestRank(), rank)));
        }
    }

    private record Score(double value, int bestRank) { }
}
