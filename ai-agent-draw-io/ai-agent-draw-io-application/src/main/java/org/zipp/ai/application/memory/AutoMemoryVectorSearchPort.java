package org.zipp.ai.application.memory;

import java.util.List;

/** Query-only side of the rebuildable Memory vector store; hits are score-descending. */
public interface AutoMemoryVectorSearchPort {
    List<AutoMemoryVectorSearchHit> search(AutoMemoryVectorSearchQuery query, int topK);
}
