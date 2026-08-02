package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.memory.AutoMemoryVectorStorePort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pinecone projection with opaque owner/scope partitions and no content-bearing metadata. */
public final class PineconeAutoMemoryVectorStoreAdapter implements AutoMemoryVectorStorePort {
    public static final Set<String> METADATA_FIELDS = Set.of(
            "memory_owner_partition",
            "memory_scope_partition",
            "memory_scope_type",
            "memory_candidate_kind",
            "memory_candidate_state",
            "memory_projection_revision");
    private final PineconeVectorClient client;
    private final String namespace;
    private final SecretKeySpec partitionKey;

    public PineconeAutoMemoryVectorStoreAdapter(
            PineconeVectorClient client,
            String namespace,
            String partitionSecret
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = required(namespace, "namespace");
        String secret = required(partitionSecret, "partitionSecret");
        this.partitionKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public List<float[]> embedPassages(List<String> texts) {
        return client.embed(List.copyOf(texts), "passage");
    }

    @Override
    public void upsert(List<AutoMemoryVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        client.upsert(namespace, List.copyOf(vectors).stream()
                .map(vector -> new PineconeVectorRecord(
                        vector.document().vectorId(),
                        vector.values(),
                        metadata(vector.document())))
                .toList());
    }

    @Override
    public Set<String> existingVectorIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return Set.of();
        }
        List<String> requested = List.copyOf(vectorIds);
        java.util.HashSet<String> existing = new java.util.HashSet<>();
        for (int start = 0; start < requested.size(); start += 100) {
            existing.addAll(client.fetchExisting(
                    namespace,
                    requested.subList(start, Math.min(start + 100, requested.size()))));
        }
        return Set.copyOf(existing);
    }

    @Override
    public Set<String> searchableVectorIds(List<AutoMemoryVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return Set.of();
        }
        java.util.HashSet<String> searchable = new java.util.HashSet<>();
        for (AutoMemoryVector vector : List.copyOf(vectors)) {
            AutoMemoryVectorDocument document = vector.document();
            boolean found = client.queryMatches(
                            namespace,
                            vector.values(),
                            100,
                            exactMetadataFilter(document))
                    .stream()
                    .anyMatch(match -> document.vectorId().equals(match.id()));
            if (found) {
                searchable.add(document.vectorId());
            }
        }
        return Set.copyOf(searchable);
    }

    @Override
    public void delete(List<String> vectorIds) {
        client.delete(namespace, vectorIds == null ? List.of() : List.copyOf(vectorIds));
    }

    @Override
    public List<AutoMemoryVectorSearchHit> search(AutoMemoryVectorSearchQuery query, int topK) {
        Objects.requireNonNull(query, "query");
        if (topK < 1 || topK > 32) {
            throw new IllegalArgumentException("topK must be between 1 and 32");
        }
        List<AutoMemoryScope> scopes = query.authorizedScopes();
        String ownerKey = scopes.get(0).ownerKey();
        if (scopes.stream().anyMatch(scope -> !ownerKey.equals(scope.ownerKey()))) {
            throw new IllegalArgumentException("authorized scopes must share one owner");
        }
        List<String> scopePartitions = scopes.stream()
                .map(this::scopePartition)
                .toList();
        float[] queryVector = client.embedOne(query.userContent(), "query");
        Map<String, Object> filter = Map.of("$and", List.of(
                Map.of("memory_owner_partition", Map.of("$eq", ownerPartition(ownerKey))),
                Map.of("memory_scope_partition", Map.of("$in", scopePartitions)),
                Map.of("memory_candidate_kind", Map.of("$in", names(query.kinds()))),
                Map.of("memory_candidate_state", Map.of("$in", names(query.states())))));
        return client.queryMatches(namespace, queryVector, topK, filter).stream()
                // Provider output is untrusted; application ranking follows the returned score.
                .sorted(Comparator.comparingDouble(PineconeVectorMatch::score).reversed())
                .map(match -> new AutoMemoryVectorSearchHit(match.id(), match.score()))
                .toList();
    }

    private static List<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private Map<String, Object> metadata(AutoMemoryVectorDocument document) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memory_owner_partition", ownerPartition(document.scope().ownerKey()));
        metadata.put("memory_scope_partition", scopePartition(document.scope()));
        metadata.put("memory_scope_type", document.scope().type().name());
        metadata.put("memory_candidate_kind", document.kind().name());
        metadata.put("memory_candidate_state", document.state().name());
        metadata.put("memory_projection_revision", document.projectionRevision());
        return Map.copyOf(metadata);
    }

    private Map<String, Object> exactMetadataFilter(AutoMemoryVectorDocument document) {
        // Readiness uses the same opaque lifecycle metadata that production recall filters on.
        List<Map<String, Object>> predicates = metadata(document).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of(
                        entry.getKey(), Map.of("$eq", entry.getValue())))
                .toList();
        return Map.of("$and", predicates);
    }

    private String ownerPartition(String ownerKey) {
        return hmac("memory-owner\u001f" + required(ownerKey, "ownerKey"));
    }

    private String scopePartition(AutoMemoryScope scope) {
        return hmac("memory-scope\u001f" + scope.ownerKey() + "\u001f"
                + scope.type().name() + "\u001f" + scope.scopeKey());
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(partitionKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
