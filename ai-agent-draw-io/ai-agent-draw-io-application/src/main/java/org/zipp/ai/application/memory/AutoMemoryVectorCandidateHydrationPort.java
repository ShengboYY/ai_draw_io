package org.zipp.ai.application.memory;

import java.util.List;

/** Revalidates vector identities against the current authoritative Memory state. */
public interface AutoMemoryVectorCandidateHydrationPort {
    List<AutoMemoryExtractionCandidate> hydrate(
            AutoMemoryConsolidationQuery query,
            List<String> rankedVectorIds);
}
