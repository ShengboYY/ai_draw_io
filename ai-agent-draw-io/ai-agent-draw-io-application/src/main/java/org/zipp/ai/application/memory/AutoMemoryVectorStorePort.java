package org.zipp.ai.application.memory;

import java.util.List;
import java.util.Set;

/** Memory-specific vector capability; MySQL remains the authority for every returned identity. */
public interface AutoMemoryVectorStorePort {
    List<float[]> embedPassages(List<String> texts);

    void upsert(List<AutoMemoryVector> vectors);

    Set<String> existingVectorIds(List<String> vectorIds);

    void delete(List<String> vectorIds);

    List<String> search(AutoMemoryConsolidationQuery query, int topK);
}
