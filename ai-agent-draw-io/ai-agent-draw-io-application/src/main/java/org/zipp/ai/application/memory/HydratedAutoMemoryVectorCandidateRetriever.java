package org.zipp.ai.application.memory;

import java.util.List;
import java.util.Objects;

/** Retrieves opaque vector hits and resolves them through the authoritative Memory store. */
final class HydratedAutoMemoryVectorCandidateRetriever {
    private final AutoMemoryVectorStorePort vectors;
    private final AutoMemoryVectorCandidateHydrationPort hydration;

    HydratedAutoMemoryVectorCandidateRetriever(
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorCandidateHydrationPort hydration
    ) {
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.hydration = Objects.requireNonNull(hydration, "hydration");
    }

    Result retrieve(AutoMemoryConsolidationQuery query, int topK) {
        List<String> vectorIds = vectors.search(query, topK);
        return new Result(vectorIds.size(), hydration.hydrate(query, vectorIds));
    }

    record Result(int vectorHitCount, List<AutoMemoryExtractionCandidate> candidates) {
        Result {
            candidates = List.copyOf(candidates);
        }
    }
}
