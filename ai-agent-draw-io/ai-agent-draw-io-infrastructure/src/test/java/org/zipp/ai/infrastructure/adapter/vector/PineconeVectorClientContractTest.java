package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PineconeVectorClientContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldEmbedSeparatelyThenUpsertOnlyVectorAndOpaqueMetadata() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeVectorClient client = new PineconeVectorClient(
                "test-api-key", "https://drawio-test.svc.pinecone.io", transport, objectMapper);

        float[] vector = client.embedOne("source text stays only in inference request", "passage");
        client.upsert("drawio-retrieval-v2", List.of(new PineconeVectorRecord(
                "rc_chunk1_ig2",
                vector,
                opaqueMetadata())));

        assertEquals(2, transport.requests.size());
        RecordedRequest embed = transport.requests.get(0);
        assertEquals(URI.create("https://api.pinecone.io/embed"), embed.uri);
        assertEquals("2025-10", embed.headers.get("X-Pinecone-Api-Version"));
        assertTrue(embed.body.contains("source text stays only in inference request"));
        assertTrue(embed.body.contains("\"truncate\":\"NONE\""));

        RecordedRequest upsert = transport.requests.get(1);
        assertEquals(URI.create("https://drawio-test.svc.pinecone.io/vectors/upsert"), upsert.uri);
        JsonNode body = objectMapper.readTree(upsert.body);
        JsonNode record = body.path("vectors").get(0);
        assertEquals(1024, record.path("values").size());
        assertEquals("rc_chunk1_ig2", record.path("id").asText());
        assertFalse(upsert.body.contains("source text stays only in inference request"));
        assertFalse(upsert.body.contains("chunk_text"));
        assertFalse(upsert.body.contains("filename"));
        assertEquals("mat_opaque", record.path("metadata").path("material_id").asText());
    }

    @Test
    void shouldRejectContentBearingMetadataBeforeCallingPinecone() {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeVectorClient client = new PineconeVectorClient(
                "test-api-key", "https://drawio-test.svc.pinecone.io", transport, objectMapper);
        Map<String, Object> metadata = opaqueMetadata();
        metadata.put("filename", "Agile Practice Guide.pdf");

        assertThrows(IllegalArgumentException.class, () -> client.upsert(
                "drawio-retrieval-v2",
                List.of(new PineconeVectorRecord("rc_chunk1_ig2", new float[1024], metadata))));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void shouldQueryAndDeleteThroughVectorEndpoints() {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeVectorClient client = new PineconeVectorClient(
                "test-api-key", "https://drawio-test.svc.pinecone.io", transport, objectMapper);

        List<String> matches = client.query("drawio-retrieval-v2", new float[1024], 8,
                Map.of("tenant_key", Map.of("$eq", "tenant_hmac")));
        client.delete("drawio-retrieval-v2", List.of("rc_chunk1_ig2"));

        assertEquals(List.of("rc_chunk1_ig2"), matches);
        assertEquals("/query", transport.requests.get(0).uri.getPath());
        assertTrue(transport.requests.get(0).body.contains("\"$eq\":\"tenant_hmac\""));
        assertEquals("/vectors/delete", transport.requests.get(1).uri.getPath());
    }

    private Map<String, Object> opaqueMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_key", "tenant_hmac");
        metadata.put("material_id", "mat_opaque");
        metadata.put("version_id", "ver_opaque");
        metadata.put("revision_id", "rev_opaque");
        metadata.put("retrieval_chunk_id", "rc_chunk1");
        metadata.put("chunk_type", "CONTENT");
        metadata.put("modality", "TEXT");
        metadata.put("page_no", 12);
        metadata.put("language", "zh");
        metadata.put("index_generation_id", "ig_2");
        return metadata;
    }

    private static final class RecordingTransport implements PineconeHttpTransport {
        private final ObjectMapper objectMapper;
        private final List<RecordedRequest> requests = new ArrayList<>();

        private RecordingTransport(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public PineconeHttpResponse exchange(String method, URI uri,
                                             Map<String, String> headers, String body) throws Exception {
            requests.add(new RecordedRequest(method, uri, headers, body));
            if (uri.getPath().equals("/embed")) {
                return new PineconeHttpResponse(200, embeddingResponse());
            }
            if (uri.getPath().equals("/query")) {
                return new PineconeHttpResponse(200,
                        "{\"matches\":[{\"id\":\"rc_chunk1_ig2\",\"score\":0.91}]}");
            }
            return new PineconeHttpResponse(200, "{}");
        }

        private String embeddingResponse() throws Exception {
            Map<String, Object> embedding = Map.of("values", new float[1024]);
            return objectMapper.writeValueAsString(Map.of(
                    "model", "multilingual-e5-large",
                    "vector_type", "dense",
                    "data", List.of(embedding),
                    "usage", Map.of("total_tokens", 7)));
        }
    }

    private record RecordedRequest(String method, URI uri, Map<String, String> headers, String body) {
    }
}
