package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResearchLlmRerankerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldKeepOnlyPermittedIdsAndBackfillTheDenseOrder() throws InterruptedException {
        ResearchLlmReranker reranker = new ResearchLlmReranker(json, (request, model) ->
                new ResearchLlmReranker.Completion(
                        "{\"rankedIds\":[\"c03\",\"unknown\",\"c01\",\"c03\"]}",
                        120, 80, 12));
        List<ResearchLlmReranker.Candidate> candidates = List.of(
                new ResearchLlmReranker.Candidate("vector-1", "source:v1", "first"),
                new ResearchLlmReranker.Candidate("vector-2", "source:v1", "second"),
                new ResearchLlmReranker.Candidate("vector-3", "source:v1", "third"));

        ResearchLlmReranker.Result result = reranker.rerank("draw the recovery flow", candidates,
                "test-model");

        assertEquals(List.of("vector-3", "vector-1", "vector-2"), result.vectorIds());
        assertEquals(120, result.latencyMillis());
        assertEquals(80, result.promptTokens());
        assertEquals(12, result.completionTokens());
    }

    @Test
    void shouldRejectNonJsonOutputWithoutChangingDenseOrder() throws InterruptedException {
        ResearchLlmReranker reranker = new ResearchLlmReranker(json, (request, model) ->
                new ResearchLlmReranker.Completion("c02, c01", 9, 0, 0));
        List<ResearchLlmReranker.Candidate> candidates = List.of(
                new ResearchLlmReranker.Candidate("vector-1", "source:v1", "first"),
                new ResearchLlmReranker.Candidate("vector-2", "source:v1", "second"));

        ResearchLlmReranker.Result result = reranker.rerank("question", candidates, "test-model");

        assertEquals(List.of("vector-1", "vector-2"), result.vectorIds());
        assertFalse(result.modelOutputAccepted());
    }

    @Test
    void shouldRejectJsonThatContainsNoPermittedId() throws InterruptedException {
        ResearchLlmReranker reranker = new ResearchLlmReranker(json, (request, model) ->
                new ResearchLlmReranker.Completion("{\"rankedIds\":[\"unknown\"]}", 9, 0, 0));
        List<ResearchLlmReranker.Candidate> candidates = List.of(
                new ResearchLlmReranker.Candidate("vector-1", "source:v1", "first"));

        ResearchLlmReranker.Result result = reranker.rerank("question", candidates, "test-model");

        assertEquals(List.of("vector-1"), result.vectorIds());
        assertFalse(result.modelOutputAccepted());
    }
}
