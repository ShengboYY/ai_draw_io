package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class FakeRetrievalVectorIndex implements RetrievalVectorIndex {
    private final Map<String, VectorProjection> records = new LinkedHashMap<>();
    private boolean readinessVisible = true;
    private boolean failNextDelete;

    @Override
    public void upsert(List<VectorProjection> projections) {
        projections.forEach(projection -> records.put(projection.vectorId(), projection));
    }

    @Override
    public Set<String> existingVectorIds(List<String> vectorIds) {
        if (!readinessVisible) return Set.of();
        return vectorIds.stream().filter(records::containsKey).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<String> query(float[] vector, String tenantKey, int topK) {
        return records.values().stream()
                .filter(record -> tenantKey.equals(record.metadata().get("tenant_key")))
                .limit(topK)
                .map(VectorProjection::retrievalChunkId)
                .toList();
    }

    @Override
    public VectorIdPage listVectorIds(String paginationToken, int limit) {
        List<String> ids = records.keySet().stream().sorted().limit(limit).toList();
        return new VectorIdPage(ids, null);
    }

    @Override
    public void delete(List<String> vectorIds) {
        if (failNextDelete) {
            failNextDelete = false;
            throw new IllegalStateException("provider delete unavailable");
        }
        vectorIds.forEach(records::remove);
    }

    public String serializedRecords() {
        List<String> safeRecords = new ArrayList<>();
        for (VectorProjection record : records.values()) {
            safeRecords.add(record.vectorId() + record.metadata() + Arrays.toString(record.values()));
        }
        return String.join("\n", safeRecords);
    }

    public void setReadinessVisible(boolean readinessVisible) {
        this.readinessVisible = readinessVisible;
    }

    public void failNextDelete() {
        failNextDelete = true;
    }
}
