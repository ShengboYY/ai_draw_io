package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.domain.material.model.valobj.MaterialVectorLocation;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt;
import org.zipp.ai.domain.material.port.MaterialDeletionVectorPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deletes explicit vector IDs only; metadata filters and delete-all are intentionally unavailable. */
public final class PineconeMaterialDeletionAdapter implements MaterialDeletionVectorPort {
    private final PineconeVectorClient client;
    private final String configuredIndexName;

    public PineconeMaterialDeletionAdapter(PineconeVectorClient client, String configuredIndexName) {
        this.client = Objects.requireNonNull(client, "client");
        if (configuredIndexName == null || configuredIndexName.isBlank()) {
            throw new IllegalArgumentException("Pinecone index name is required");
        }
        this.configuredIndexName = configuredIndexName.trim();
    }

    @Override
    public MaterialDeletionReceipt delete(List<MaterialVectorLocation> vectors) {
        List<MaterialVectorLocation> exact = List.copyOf(vectors);
        List<MaterialVectorLocation> owned = exact.stream()
                .filter(location -> configuredIndexName.equals(location.indexName())).toList();
        Map<String, List<String>> byNamespace = owned.stream().collect(Collectors.groupingBy(
                MaterialVectorLocation::namespace,
                Collectors.mapping(MaterialVectorLocation::vectorId, Collectors.toList())));
        java.util.ArrayList<String> requestIds = new java.util.ArrayList<>();
        byNamespace.forEach((namespace, ids) -> {
            for (int start = 0; start < ids.size(); start += 100) {
                String requestId = client.deleteWithRequestId(namespace,
                        ids.subList(start, Math.min(start + 100, ids.size())));
                if (requestId == null || requestId.isBlank()) {
                    throw new IllegalStateException("Pinecone deletion response has no request id");
                }
                requestIds.add(requestId);
                if (!client.fetchExisting(namespace,
                        ids.subList(start, Math.min(start + 100, ids.size()))).isEmpty()) {
                    throw new IllegalStateException("Pinecone still contains vectors after exact deletion");
                }
            }
        });
        // Another profile will claim the same durable stage for any remaining index.
        return MaterialDeletionReceipt.vector(owned.size(), requestIds, configuredIndexName,
                owned.size() == exact.size());
    }
}
