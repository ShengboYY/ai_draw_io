package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.zipp.ai.domain.retrieval.port.RetryableRetrievalException;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;

/**
 * REST spike for a standard Pinecone dense index. Embedding is always a separate inference call;
 * the data-plane adapter accepts vectors only and enforces the opaque metadata allowlist.
 */
public final class PineconeVectorClient {

    private static final String API_VERSION = "2025-10";
    private static final URI INFERENCE_URI = URI.create("https://api.pinecone.io/embed");
    private static final Set<String> METADATA_ALLOWLIST = Set.of(
            "tenant_key", "material_id", "version_id", "revision_id", "retrieval_chunk_id",
            "chunk_type", "modality", "page_no", "language", "index_generation_id");
    private static final Set<String> FILTER_OPERATORS = Set.of(
            "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists");

    private final String apiKey;
    private final URI indexHost;
    private final String embeddingModel;
    private final int dimension;
    private final PineconeHttpTransport transport;
    private final ObjectMapper objectMapper;

    public PineconeVectorClient(String apiKey, String indexHost, String embeddingModel,
                                int dimension, ObjectMapper objectMapper) {
        this(apiKey, indexHost, embeddingModel, dimension, new JdkPineconeHttpTransport(), objectMapper);
    }

    PineconeVectorClient(String apiKey, String indexHost, String embeddingModel, int dimension,
                         PineconeHttpTransport transport, ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Pinecone API key is required");
        if (indexHost == null || indexHost.isBlank()) throw new IllegalArgumentException("Pinecone index host is required");
        this.apiKey = apiKey;
        this.indexHost = URI.create(indexHost.replaceAll("/+$", ""));
        this.embeddingModel = required(embeddingModel, "embedding model");
        if (dimension < 1) throw new IllegalArgumentException("embedding dimension must be positive");
        this.dimension = dimension;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public float[] embedOne(String text, String inputType) {
        return embed(List.of(text), inputType).get(0);
    }

    public List<float[]> embed(List<String> texts, String inputType) {
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("Embedding texts are required");
        if (!"passage".equals(inputType) && !"query".equals(inputType)) {
            throw new IllegalArgumentException("Pinecone E5 input type must be passage or query");
        }
        int maximumBatchSize = "passage".equals(inputType) ? 96 : 3;
        if (texts.size() > maximumBatchSize || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Pinecone E5 embedding batch is invalid");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", embeddingModel);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("input_type", inputType);
        // Chunking owns the token budget; provider-side truncation would silently corrupt evidence.
        parameters.put("truncate", "NONE");
        ArrayNode inputs = body.putArray("inputs");
        texts.forEach(text -> inputs.addObject().put("text", text));

        JsonNode response = exchange(INFERENCE_URI, body);
        if (!embeddingModel.equals(response.path("model").asText())) {
            throw new IllegalStateException("Pinecone embedding model does not match the generation profile");
        }
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("Pinecone embedding result count does not match the request");
        }
        List<float[]> result = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode values = item.path("values");
            if (!values.isArray() || values.size() != dimension) {
                throw new IllegalStateException("Pinecone embedding dimension is not " + dimension);
            }
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) vector[i] = (float) values.get(i).asDouble();
            result.add(vector);
        }
        return List.copyOf(result);
    }

    public void upsert(String namespace, List<PineconeVectorRecord> records) {
        if (records == null || records.isEmpty()) return;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("namespace", required(namespace, "namespace"));
        ArrayNode vectors = body.putArray("vectors");
        for (PineconeVectorRecord record : records) {
            validateVector(record.values());
            validateMetadata(record.metadata());
            ObjectNode vector = vectors.addObject();
            vector.put("id", record.id());
            ArrayNode values = vector.putArray("values");
            for (float value : record.values()) values.add(value);
            vector.set("metadata", objectMapper.valueToTree(record.metadata()));
        }
        exchange(indexUri("/vectors/upsert"), body);
    }

    public List<String> query(String namespace, float[] vector, int topK, Map<String, Object> filter) {
        validateVector(vector);
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK must be between 1 and 100");
        validateFilter(filter == null ? Map.of() : filter);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("namespace", required(namespace, "namespace"));
        body.put("topK", topK);
        body.put("includeValues", false);
        body.put("includeMetadata", false);
        ArrayNode values = body.putArray("vector");
        for (float value : vector) values.add(value);
        if (filter != null && !filter.isEmpty()) body.set("filter", objectMapper.valueToTree(filter));
        JsonNode response = exchange(indexUri("/query"), body);
        List<String> ids = new ArrayList<>();
        response.path("matches").forEach(match -> ids.add(match.path("id").asText()));
        return ids;
    }

    public void delete(String namespace, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("namespace", required(namespace, "namespace"));
        ArrayNode idArray = body.putArray("ids");
        ids.forEach(id -> idArray.add(required(id, "vector id")));
        exchange(indexUri("/vectors/delete"), body);
    }

    public Set<String> fetchExisting(String namespace, List<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        if (ids.size() > 100) throw new IllegalArgumentException("Pinecone fetch batch cannot exceed 100 ids");
        StringBuilder query = new StringBuilder("?namespace=")
                .append(encodeQuery(required(namespace, "namespace")));
        for (String id : ids) query.append("&ids=").append(encodeQuery(required(id, "vector id")));
        JsonNode response = exchange("GET", indexUri("/vectors/fetch" + query), "");
        JsonNode vectors = response.path("vectors");
        if (!vectors.isObject()) throw new IllegalStateException("Pinecone fetch response has no vectors object");
        Set<String> requested = Set.copyOf(ids);
        java.util.HashSet<String> existing = new java.util.HashSet<>();
        vectors.fieldNames().forEachRemaining(id -> {
            if (!requested.contains(id)) {
                throw new IllegalStateException("Pinecone fetch returned an unrequested vector id");
            }
            existing.add(id);
        });
        return Set.copyOf(existing);
    }

    public VectorIdPage listVectorIds(String namespace, String paginationToken, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("list limit must be between 1 and 100");
        StringBuilder query = new StringBuilder("?namespace=")
                .append(encodeQuery(required(namespace, "namespace")))
                .append("&limit=").append(limit);
        if (paginationToken != null && !paginationToken.isBlank()) {
            query.append("&paginationToken=").append(encodeQuery(paginationToken.trim()));
        }
        JsonNode response = exchange("GET", indexUri("/vectors/list" + query), "");
        List<String> ids = new ArrayList<>();
        response.path("vectors").forEach(vector -> ids.add(required(vector.path("id").asText(), "vector id")));
        String next = response.path("pagination").path("next").asText(null);
        return new VectorIdPage(ids, next);
    }

    private JsonNode exchange(URI uri, ObjectNode body) {
        return exchange("POST", uri, body.toString());
    }

    private JsonNode exchange(String method, URI uri, String body) {
        try {
            PineconeHttpResponse response = transport.exchange(method, uri, headers(), body);
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new RetryableRetrievalException("Pinecone returned HTTP " + response.statusCode(),
                        retryAfter(response.retryAfter()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Pinecone returned HTTP " + response.statusCode());
            }
            return response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Pinecone request failed", e);
        }
    }

    private Duration retryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.trim().matches("[0-9]+")) {
                return Duration.ofSeconds(Long.parseLong(value.trim()));
            }
            Duration delay = Duration.between(Instant.now(), ZonedDateTime.parse(
                    value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() || delay.isZero() ? null : delay;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Api-Key", apiKey);
        headers.put("Content-Type", "application/json");
        headers.put("X-Pinecone-Api-Version", API_VERSION);
        return headers;
    }

    private URI indexUri(String path) {
        return URI.create(indexHost.toString() + path);
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void validateVector(float[] values) {
        if (values == null || values.length != dimension) {
            throw new IllegalArgumentException("Vector dimension must be " + dimension);
        }
    }

    private void validateMetadata(Map<String, Object> metadata) {
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!METADATA_ALLOWLIST.contains(entry.getKey())) {
                throw new IllegalArgumentException("Pinecone metadata field is not allowed: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Pinecone metadata values must be scalar");
            }
        }
    }

    private void validateFilter(Map<String, Object> filter) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if ("$and".equals(entry.getKey()) || "$or".equals(entry.getKey())) {
                if (!(entry.getValue() instanceof List<?> conditions)) {
                    throw new IllegalArgumentException("Logical Pinecone filter requires a list");
                }
                for (Object condition : conditions) {
                    if (!(condition instanceof Map<?, ?> conditionMap)) {
                        throw new IllegalArgumentException("Pinecone filter condition must be an object");
                    }
                    validateFilter(toStringKeyMap(conditionMap));
                }
                continue;
            }
            if (!METADATA_ALLOWLIST.contains(entry.getKey())) {
                throw new IllegalArgumentException("Pinecone filter field is not allowed: " + entry.getKey());
            }
            Object predicate = entry.getValue();
            if (predicate instanceof Map<?, ?> operators) {
                for (Map.Entry<String, Object> operator : toStringKeyMap(operators).entrySet()) {
                    if (!FILTER_OPERATORS.contains(operator.getKey())) {
                        throw new IllegalArgumentException("Pinecone filter operator is not allowed");
                    }
                }
            } else if (!(predicate instanceof String) && !(predicate instanceof Number)
                    && !(predicate instanceof Boolean)) {
                throw new IllegalArgumentException("Pinecone filter predicate is invalid");
            }
        }
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
