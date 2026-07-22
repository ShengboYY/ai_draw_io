package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only listwise reranker that can never introduce a candidate outside the dense top-40.
 */
final class ResearchLlmReranker {

    static final String FINGERPRINT = "llm-listwise-rerank-v1:top40:json-ranked-ids:temperature0";
    private static final int MAX_CANDIDATE_TEXT_CHARS = 800;

    private final ObjectMapper json;
    private final CompletionClient completionClient;

    ResearchLlmReranker(ObjectMapper json, CompletionClient completionClient) {
        this.json = json;
        this.completionClient = completionClient;
    }

    Result rerank(String query, List<Candidate> candidates, String model) throws InterruptedException {
        Completion completion = completionClient.complete(prompt(query, candidates), model);
        List<String> denseOrder = candidates.stream().map(Candidate::vectorId).toList();
        List<String> reranked = parseRankedIds(completion.content(), denseOrder);
        boolean accepted = !reranked.equals(denseOrder) || isAcceptedJson(completion.content(), denseOrder);
        return new Result(reranked, accepted, completion.latencyMillis(), completion.promptTokens(),
                completion.completionTokens());
    }

    private List<String> parseRankedIds(String content, List<String> denseOrder) {
        try {
            JsonNode ids = json.readTree(content).path("rankedIds");
            if (!ids.isArray()) return denseOrder;
            Set<String> permitted = Set.copyOf(denseOrder);
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ids.forEach(value -> {
                String id = value.asText();
                if (permitted.contains(id)) ordered.add(id);
            });
            ordered.addAll(denseOrder);
            return List.copyOf(ordered);
        } catch (Exception ignored) {
            return denseOrder;
        }
    }

    private boolean isAcceptedJson(String content, List<String> denseOrder) {
        try {
            JsonNode ids = json.readTree(content).path("rankedIds");
            Set<String> permitted = Set.copyOf(denseOrder);
            if (!ids.isArray() || ids.isEmpty()) return false;
            for (JsonNode id : ids) {
                if (permitted.contains(id.asText())) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String prompt(String query, List<Candidate> candidates) {
        StringBuilder prompt = new StringBuilder("""
                You rerank synthetic evidence passages for a draw.io RAG agent. Return JSON only:
                {"rankedIds":["candidate-id",...]}. Rank every supplied candidate by direct support for the
                user request. Use only supplied IDs. Do not answer the request and do not invent facts.

                User request:
                """).append(query).append("\n\nCandidates:\n");
        for (Candidate candidate : candidates) {
            prompt.append("id=").append(candidate.vectorId())
                    .append(" source=").append(candidate.sourceVersion())
                    .append("\ntext=").append(clip(candidate.retrievalText())).append("\n\n");
        }
        return prompt.toString();
    }

    private String clip(String text) {
        return text.length() <= MAX_CANDIDATE_TEXT_CHARS ? text
                : text.substring(0, MAX_CANDIDATE_TEXT_CHARS) + "…";
    }

    interface CompletionClient {
        Completion complete(String request, String model) throws InterruptedException;
    }

    record Candidate(String vectorId, String sourceVersion, String retrievalText) { }

    record Completion(String content, long latencyMillis, int promptTokens, int completionTokens) { }

    record Result(List<String> vectorIds, boolean modelOutputAccepted, long latencyMillis,
                  int promptTokens, int completionTokens) { }
}
