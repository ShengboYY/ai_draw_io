package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in live contract. It is skipped unless a disposable 1024-dimension index is configured. */
class PineconeVectorClientLiveContractTest {

    @Test
    void shouldEmbedUpsertQueryAndDeleteAgainstConfiguredIndex() {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank());

        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, new ObjectMapper());
        String id = "wp0_" + UUID.randomUUID();
        String tenantKey = "wp0_" + UUID.randomUUID();
        float[] vector = client.embedOne("WP0 Pinecone standalone embedding contract", "passage");
        try {
            client.upsert("drawio-retrieval-v2", List.of(new PineconeVectorRecord(
                    id, vector, metadata(tenantKey, id))));
            client.query("drawio-retrieval-v2", vector, 1,
                    Map.of("tenant_key", Map.of("$eq", tenantKey)));
        } finally {
            // Always remove the disposable vector if the upsert reached Pinecone.
            client.delete("drawio-retrieval-v2", List.of(id));
        }
    }

    private Map<String, Object> metadata(String tenantKey, String chunkId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_key", tenantKey);
        metadata.put("material_id", "mat_wp0");
        metadata.put("version_id", "ver_wp0");
        metadata.put("revision_id", "rev_wp0");
        metadata.put("retrieval_chunk_id", chunkId);
        metadata.put("chunk_type", "CONTENT");
        metadata.put("modality", "TEXT");
        metadata.put("page_no", 1);
        metadata.put("language", "en");
        metadata.put("index_generation_id", "ig_wp0");
        return metadata;
    }
}
