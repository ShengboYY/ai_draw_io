package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

public final class PineconeRetrievalVectorIndexAdapter implements RetrievalVectorIndex {
    private final PineconeVectorClient client;
    private final String namespace;

    public PineconeRetrievalVectorIndexAdapter(PineconeVectorClient client, String namespace) {
        this.client = Objects.requireNonNull(client, "client");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        this.namespace = namespace.trim();
    }

    @Override
    public void upsert(List<VectorProjection> projections) {
        client.upsert(namespace, List.copyOf(projections).stream().map(projection ->
                new PineconeVectorRecord(projection.vectorId(), projection.values(), projection.metadata()))
                .toList());
    }

    @Override
    public Set<String> existingVectorIds(List<String> vectorIds) {
        List<String> requested = List.copyOf(vectorIds);
        HashSet<String> existing = new HashSet<>();
        // Keep fetch URLs bounded while supporting large revision manifests.
        for (int start = 0; start < requested.size(); start += 100) {
            existing.addAll(client.fetchExisting(namespace,
                    requested.subList(start, Math.min(start + 100, requested.size()))));
        }
        return Set.copyOf(existing);
    }

    @Override
    public List<String> query(float[] vector, String tenantKey, int topK) {
        return client.query(namespace, vector, topK, Map.of("tenant_key", Map.of("$eq", tenantKey)));
    }

    @Override
    public VectorIdPage listVectorIds(String paginationToken, int limit) {
        return client.listVectorIds(namespace, paginationToken, limit);
    }

    @Override
    public void delete(List<String> vectorIds) {
        client.delete(namespace, List.copyOf(vectorIds));
    }
}
