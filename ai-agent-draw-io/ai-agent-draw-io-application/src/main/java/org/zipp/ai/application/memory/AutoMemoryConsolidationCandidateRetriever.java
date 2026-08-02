package org.zipp.ai.application.memory;

import java.util.List;

/** Replaceable retrieval boundary; returned candidates remain untrusted model context. */
public interface AutoMemoryConsolidationCandidateRetriever {
    List<AutoMemoryExtractionCandidate> retrieve(AutoMemoryConsolidationQuery query);
}
