package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;

import java.util.List;

public interface RetrievalVectorIndex {
    void upsert(VectorProjection projection);
    List<String> query(float[] vector, String tenantKey, int topK);
    boolean delete(String vectorId);
}
