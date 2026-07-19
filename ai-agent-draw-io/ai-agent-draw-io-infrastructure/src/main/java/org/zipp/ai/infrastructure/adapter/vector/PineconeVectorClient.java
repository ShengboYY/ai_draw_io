package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST spike for a standard Pinecone dense index. Embedding is always a separate inference call;
 * the data-plane adapter accepts vectors only and enforces the opaque metadata allowlist.
 */
public final class PineconeVectorClient {

    static final int DIMENSION = 1024;
    private static final String API_VERSION = "2025-10";
    private static final URI INFERENCE_URI = URI.create("https://api.pinecone.io/embed");
    private static final Set<String> METADATA_ALLOWLIST = Set.of(
            "tenant_key", "material_id", "version_id", "revision_id", "retrieval_chunk_id",
            "chunk_type", "modality", "page_no", "language", "index_generation_id");
    private static final Set<String> FILTER_OPERATORS = Set.of(
            "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists");

    private final String apiKey;
    private final URI indexHost;
    private final PineconeHttpTransport transport;
    private final ObjectMapper objectMapper;

    public PineconeVectorClient(String apiKey, String indexHost, ObjectMapper objectMapper) {
        this(apiKey, indexHost, new JdkPineconeHttpTransport(), objectMapper);
    }

    PineconeVectorClient(String apiKey, String indexHost,
                         PineconeHttpTransport transport, ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Pinecone API key is required");
        if (indexHost == null || indexHost.isBlank()) throw new IllegalArgumentException("Pinecone index host is required");
        this.apiKey = apiKey;
        this.indexHost = URI.create(indexHost.replaceAll("/+$", ""));
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public float[] embedOne(String text, String inputType) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text is required");
        if (!"passage".equals(inputType) && !"query".equals(inputType)) {
            throw new IllegalArgumentException("Pinecone E5 input type must be passage or query");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "multilingual-e5-large");
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("input_type", inputType);
        // Chunking owns the token budget; provider-side truncation would silently corrupt evidence.
        parameters.put("truncate", "NONE");
        body.putArray("inputs").addObject().put("text", text);

        JsonNode response = exchange(INFERENCE_URI, body);
        JsonNode values = response.path("data").path(0).path("values");
        if (!values.isArray() || values.size() != DIMENSION) {
            throw new IllegalStateException("Pinecone embedding dimension is not " + DIMENSION);
        }
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) vector[i] = (float) values.get(i).asDouble();
        return vector;
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

    private JsonNode exchange(URI uri, ObjectNode body) {
        try {
            PineconeHttpResponse response = transport.exchange("POST", uri, headers(), body.toString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Pinecone returned HTTP " + response.statusCode());
            }
            return response.body() == null || response.body().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Pinecone request failed", e);
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

    private void validateVector(float[] values) {
        if (values == null || values.length != DIMENSION) {
            throw new IllegalArgumentException("Vector dimension must be " + DIMENSION);
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
