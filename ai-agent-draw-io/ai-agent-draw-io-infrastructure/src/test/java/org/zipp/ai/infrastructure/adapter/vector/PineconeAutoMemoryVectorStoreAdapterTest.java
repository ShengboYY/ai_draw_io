package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryConsolidationQuery;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.turn.TurnKey;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PineconeAutoMemoryVectorStoreAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectionMetadataContainsOnlyOpaquePartitionsAndLifecycleFields() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeAutoMemoryVectorStoreAdapter adapter = adapter(transport);
        AutoMemoryVectorDocument document = AutoMemoryVectorDocument.current(memory(), 7);

        adapter.upsert(List.of(new AutoMemoryVector(document, new float[]{1F, 2F, 3F})));

        RecordedRequest request = transport.requests.get(0);
        JsonNode record = objectMapper.readTree(request.body()).path("vectors").get(0);
        assertEquals(PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS,
                objectMapper.convertValue(record.path("metadata"), Map.class).keySet());
        assertEquals("CURRENT", record.path("metadata").path("memory_candidate_kind").asText());
        assertEquals("DISABLED", record.path("metadata").path("memory_candidate_state").asText());
        assertEquals(7, record.path("metadata").path("memory_projection_revision").asInt());
        assertFalse(request.body().contains("owner-1"));
        assertFalse(request.body().contains("Prefer concise labels"));
        assertFalse(request.body().contains("Node labels"));
    }

    @Test
    void searchFiltersOpaqueOwnerAndAuthorizedScopesBeforeTopK() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeAutoMemoryVectorStoreAdapter adapter = adapter(transport);
        AutoMemoryConsolidationQuery query = new AutoMemoryConsolidationQuery(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "chartbook-1",
                "Keep labels concise",
                16);

        assertEquals(List.of("opaque-vector-1"), adapter.search(query, 16));

        RecordedRequest embedding = transport.requests.get(0);
        RecordedRequest search = transport.requests.get(1);
        assertTrue(embedding.body().contains("Keep labels concise"));
        assertEquals("/query", search.uri().getPath());
        assertTrue(search.body().contains("memory_owner_partition"));
        assertTrue(search.body().contains("memory_scope_partition"));
        assertTrue(search.body().contains("\"$in\""));
        assertTrue(search.body().contains("CONFLICTING"));
        assertFalse(search.body().contains("owner-1"));
        assertFalse(search.body().contains("chartbook-1"));
        assertFalse(search.body().contains("Keep labels concise"));
    }

    @Test
    void generationContextSearchExcludesNonActiveAndChallengerVectors() throws Exception {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeAutoMemoryVectorStoreAdapter adapter = adapter(transport);

        List<AutoMemoryVectorSearchHit> hits = adapter.search(
                AutoMemoryVectorSearchQuery.activeContext(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "chartbook-1",
                "Use concise labels"), 16);

        assertEquals(List.of(new AutoMemoryVectorSearchHit("opaque-vector-1", 0.91d)), hits);
        String body = transport.requests.get(1).body();
        assertTrue(body.contains("CURRENT"));
        assertTrue(body.contains("ACTIVE"));
        assertFalse(body.contains("CHALLENGER"));
        assertFalse(body.contains("CONFLICTING"));
        assertFalse(body.contains("DISABLED"));
    }

    @Test
    void memorySearchRanksProviderMatchesByDescendingScore() {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        transport.queryResponse = "{\"matches\":["
                + "{\"id\":\"low\",\"score\":0.71},"
                + "{\"id\":\"high\",\"score\":0.92}]}";
        PineconeAutoMemoryVectorStoreAdapter adapter = adapter(transport);

        List<AutoMemoryVectorSearchHit> hits = adapter.search(
                AutoMemoryVectorSearchQuery.activeContext(
                        new TurnKey("owner-1", "conversation-1", "turn-1"),
                        null,
                        "Use concise labels"),
                16);

        assertEquals(List.of("high", "low"),
                hits.stream().map(AutoMemoryVectorSearchHit::vectorId).toList());
    }

    @Test
    void memoryClientRejectsAnyContentBearingMetadataField() {
        RecordingTransport transport = new RecordingTransport(objectMapper);
        PineconeVectorClient client = client(transport);

        assertThrows(IllegalArgumentException.class, () -> client.upsert(
                "auto-memory-v1",
                List.of(new PineconeVectorRecord(
                        "vector-1",
                        new float[]{1F, 2F, 3F},
                        Map.of("canonical_text", "secret content")))));
        assertTrue(transport.requests.isEmpty());
    }

    private PineconeAutoMemoryVectorStoreAdapter adapter(RecordingTransport transport) {
        return new PineconeAutoMemoryVectorStoreAdapter(
                client(transport), "auto-memory-v1", "partition-secret");
    }

    private PineconeVectorClient client(RecordingTransport transport) {
        return new PineconeVectorClient(
                "api-key",
                "https://memory-index.example",
                "multilingual-e5-large",
                3,
                transport,
                objectMapper,
                PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS);
    }

    private AutoMemory memory() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new AutoMemory(
                "memory-1",
                AutoMemoryScope.user("owner-1"),
                AutoMemoryType.PREFERENCE,
                "node-label-density",
                "Node labels",
                "Prefer concise labels",
                AutoMemoryStatus.DISABLED,
                0.9d,
                2,
                false,
                3,
                now,
                now);
    }

    private static final class RecordingTransport implements PineconeHttpTransport {
        private final ObjectMapper objectMapper;
        private final List<RecordedRequest> requests = new ArrayList<>();
        private String queryResponse =
                "{\"matches\":[{\"id\":\"opaque-vector-1\",\"score\":0.91}]}";

        private RecordingTransport(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public PineconeHttpResponse exchange(
                String method,
                URI uri,
                Map<String, String> headers,
                String body
        ) throws Exception {
            requests.add(new RecordedRequest(method, uri, body));
            if (uri.getPath().equals("/embed")) {
                int count = objectMapper.readTree(body).path("inputs").size();
                List<Map<String, Object>> data = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    data.add(Map.of("values", new float[]{1F, 2F, 3F}));
                }
                return new PineconeHttpResponse(200, objectMapper.writeValueAsString(Map.of(
                        "model", "multilingual-e5-large",
                        "data", data)));
            }
            if (uri.getPath().equals("/query")) {
                return new PineconeHttpResponse(200, queryResponse);
            }
            return new PineconeHttpResponse(200, "{}");
        }
    }

    private record RecordedRequest(String method, URI uri, String body) {
    }
}
