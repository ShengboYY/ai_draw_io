package org.zipp.ai.application.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Default deterministic retriever until a semantic adapter replaces it at composition time. */
public final class ScopedAutoMemoryConsolidationCandidateRetriever
        implements AutoMemoryConsolidationCandidateRetriever {
    private final AutoMemoryQueryPort memories;

    public ScopedAutoMemoryConsolidationCandidateRetriever(AutoMemoryQueryPort memories) {
        this.memories = Objects.requireNonNull(memories, "memories");
    }

    @Override
    public List<AutoMemoryExtractionCandidate> retrieve(
            AutoMemoryConsolidationQuery query
    ) {
        Objects.requireNonNull(query, "query");
        List<AutoMemoryScope> scopes = query.authorizedScopes();
        List<AutoMemoryExtractionCandidate> candidates = new ArrayList<>(
                scopes.size() * query.limitPerScope());
        for (AutoMemoryScope scope : scopes) {
            memories.findConsolidationCandidates(scope, query.limitPerScope())
                    .stream()
                    .map(AutoMemoryExtractionCandidate::from)
                    .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }
}
