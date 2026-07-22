package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** OpenAI-compatible completion client used only by the opt-in research reranking test. */
final class ResearchOpenAiCompletionClient implements ResearchLlmReranker.CompletionClient {

    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper json;
    private final HttpClient http;

    ResearchOpenAiCompletionClient(String baseUrl, String completionsPath, String apiKey,
                                   ObjectMapper json) {
        this.endpoint = endpoint(baseUrl, completionsPath);
        this.apiKey = required(apiKey, "MATERIAL_RAG_RERANKER_API_KEY");
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public ResearchLlmReranker.Completion complete(String request, String model)
            throws InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", required(model, "MATERIAL_RAG_RERANKER_MODEL"));
        body.put("temperature", 0);
        body.put("max_tokens", 512);
        body.putObject("thinking").put("type", "disabled");
        body.putArray("messages")
                .addObject().put("role", "user").put("content", request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        Instant started = Instant.now();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Reranker completion returned HTTP " + response.statusCode());
            }
            JsonNode payload = json.readTree(response.body());
            String content = payload.path("choices").path(0).path("message").path("content").asText();
            // A successful provider response may still omit final content after its reasoning budget.
            // The reranker records that as an invalid JSON result and safely keeps dense order.
            JsonNode usage = payload.path("usage");
            return new ResearchLlmReranker.Completion(content, elapsedMillis,
                    usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Reranker completion request failed", e);
        }
    }

    private static URI endpoint(String baseUrl, String path) {
        String base = required(baseUrl, "MATERIAL_RAG_RERANKER_BASE_URL").replaceAll("/+$", "");
        String suffix = required(path, "MATERIAL_RAG_RERANKER_COMPLETIONS_PATH");
        return URI.create(base + "/" + suffix.replaceFirst("^/+", ""));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
