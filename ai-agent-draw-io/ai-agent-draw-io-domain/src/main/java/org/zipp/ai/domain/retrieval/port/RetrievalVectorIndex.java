package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;

import java.util.List;
import java.util.Set;

public interface RetrievalVectorIndex {
    void upsert(List<VectorProjection> projections);
    Set<String> existingVectorIds(List<String> vectorIds);
    List<String> query(float[] vector, String tenantKey, int topK);
    boolean delete(String vectorId);
}
