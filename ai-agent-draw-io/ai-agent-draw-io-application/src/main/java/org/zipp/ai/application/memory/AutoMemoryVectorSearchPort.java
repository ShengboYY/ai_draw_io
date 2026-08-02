package org.zipp.ai.application.memory;

import java.util.List;

/** Query-only side of the rebuildable Memory vector store. */
public interface AutoMemoryVectorSearchPort {
    List<String> search(AutoMemoryVectorSearchQuery query, int topK);
}
