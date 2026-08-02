package org.zipp.ai.infrastructure.turn.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;

import java.util.List;
import java.util.Locale;

/** Shared safety and lifecycle setup for opt-in Memory Pinecone evaluations. */
final class AutoMemoryPineconeLiveTestSupport {
    private AutoMemoryPineconeLiveTestSupport() {
    }

    static Session connect(ObjectMapper mapper) {
        String namespace = requiredEnvironment("AUTO_MEMORY_VECTOR_PINECONE_NAMESPACE");
        String normalizedNamespace = namespace.toLowerCase(Locale.ROOT);
        if (!normalizedNamespace.contains("test")
                && !normalizedNamespace.contains("dev")
                && !normalizedNamespace.contains("eval")) {
            throw new IllegalArgumentException(
                    "Memory live evaluation requires a disposable namespace");
        }
        String model = environment(
                "AUTO_MEMORY_VECTOR_EMBEDDING_MODEL",
                environment("PINECONE_EMBEDDING_MODEL", "multilingual-e5-large"));
        int dimension = positiveInteger(environment(
                "AUTO_MEMORY_VECTOR_DIMENSION",
                environment("PINECONE_DIMENSION", "1024")));
        PineconeVectorClient client = new PineconeVectorClient(
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_API_KEY", "PINECONE_API_KEY"),
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_INDEX_HOST", "PINECONE_INDEX_HOST"),
                model,
                dimension,
                mapper,
                PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS);
        return new Session(
                client,
                new PineconeAutoMemoryVectorStoreAdapter(
                        client,
                        namespace,
                        requiredEnvironment(
                                "AUTO_MEMORY_VECTOR_PARTITION_SECRET",
                                "PINECONE_TENANT_HMAC_SECRET")),
                namespace,
                model,
                dimension);
    }

    static void waitUntilVisible(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (vectors.existingVectorIds(vectorIds).containsAll(vectorIds)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Memory vectors were not visible within 10 seconds");
    }

    static void waitUntilDeleted(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (vectors.existingVectorIds(vectorIds).isEmpty()) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Memory vectors remained after evaluation cleanup");
    }

    private static String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required Memory vector environment is missing");
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int positiveInteger(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("embedding dimension must be positive");
        }
        return parsed;
    }

    record Session(
            PineconeVectorClient client,
            PineconeAutoMemoryVectorStoreAdapter vectors,
            String namespace,
            String model,
            int dimension
    ) {
    }
}
