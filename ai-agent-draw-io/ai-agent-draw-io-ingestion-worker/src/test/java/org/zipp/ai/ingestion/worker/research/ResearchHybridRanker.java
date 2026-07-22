package org.zipp.ai.ingestion.worker.research;

import org.zipp.ai.domain.retrieval.projection.LexicalProjection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic offline lexical lane and the same weighted RRF used by online retrieval. */
final class ResearchHybridRanker {
    static final String FINGERPRINT =
            "projection-tfidf-exact-v1:word-min2:cjk-bigram:exact2:rrf-k60-lex1.2-dense1.0";
    private static final Pattern WORD = Pattern.compile(
            "[\\p{IsLatin}\\p{N}][\\p{IsLatin}\\p{N}._:/-]*");
    private static final int RRF_K = 60;

    private ResearchHybridRanker() { }

    static List<String> lexicalRank(String query, List<LexicalProjection> projections) {
        List<String> queryTerms = terms(query);
        String normalizedQuery = query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        Map<String, List<String>> documentTerms = new HashMap<>();
        for (LexicalProjection projection : projections) {
            List<String> values = new ArrayList<>();
            if (projection.wordSearchText() != null) values.addAll(terms(projection.wordSearchText()));
            if (projection.cjkSearchText() != null) values.addAll(cjkBigrams(projection.cjkSearchText()));
            documentTerms.put(projection.chunkId(), List.copyOf(values));
        }

        Set<String> uniqueQueryTerms = new LinkedHashSet<>(queryTerms);
        Map<String, Double> scores = new HashMap<>();
        for (LexicalProjection projection : projections) {
            List<String> values = documentTerms.get(projection.chunkId());
            double score = 0.0;
            for (String term : uniqueQueryTerms) {
                long frequency = values.stream().filter(term::equals).count();
                if (frequency == 0) continue;
                long documentFrequency = documentTerms.values().stream()
                        .filter(document -> document.contains(term)).count();
                double inverseDocumentFrequency = Math.log(
                        (projections.size() + 1.0) / (documentFrequency + 1.0)) + 1.0;
                score += Math.log1p(frequency) * inverseDocumentFrequency;
            }
            // Production MySQL adds the same boolean +2 boost when any projected term occurs.
            boolean exact = projection.exactTerms().stream().anyMatch(term ->
                    normalizedQuery.contains(term.normalizedTerm().toLowerCase(Locale.ROOT)));
            if (exact) score += 2.0;
            if (score > 0.0) scores.put(projection.chunkId(), score);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).limit(40).toList();
    }

    static List<String> fuse(List<String> lexicalChunkIds, List<String> denseChunkIds, int limit) {
        Map<String, Double> scores = new HashMap<>();
        addLane(scores, lexicalChunkIds, 1.2);
        addLane(scores, denseChunkIds, 1.0);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).limit(limit).toList();
    }

    private static void addLane(Map<String, Double> scores, List<String> chunkIds, double weight) {
        for (int index = 0; index < chunkIds.size(); index++) {
            scores.merge(chunkIds.get(index), weight / (RRF_K + index + 1), Double::sum);
        }
    }

    private static List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String value = matcher.group();
            if (value.codePointCount(0, value.length()) >= 2) result.add(value);
        }
        result.addAll(cjkBigrams(text));
        return List.copyOf(result);
    }

    private static List<String> cjkBigrams(String text) {
        List<Integer> run = new ArrayList<>();
        List<String> result = new ArrayList<>();
        text.codePoints().forEach(codePoint -> {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                run.add(codePoint);
            } else {
                appendCjkRun(run, result);
            }
        });
        appendCjkRun(run, result);
        return List.copyOf(result);
    }

    private static void appendCjkRun(List<Integer> run, List<String> result) {
        if (run.size() == 1) result.add(new String(Character.toChars(run.get(0))));
        for (int index = 0; index + 1 < run.size(); index++) {
            result.add(new String(Character.toChars(run.get(index)))
                    + new String(Character.toChars(run.get(index + 1))));
        }
        run.clear();
    }
}
