package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    public List<String> query(float[] vector, String tenantKey, int topK) {
        return client.query(namespace, vector, topK, Map.of("tenant_key", Map.of("$eq", tenantKey)));
    }

    @Override
    public boolean delete(String vectorId) {
        client.delete(namespace, List.of(vectorId));
        return true;
    }
}
