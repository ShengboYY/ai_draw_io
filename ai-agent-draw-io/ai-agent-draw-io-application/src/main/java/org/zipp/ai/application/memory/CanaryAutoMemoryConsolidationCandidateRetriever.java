package org.zipp.ai.application.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Locally trials hydrated vector candidates while retaining SQL fill and failure fallback. */
public final class CanaryAutoMemoryConsolidationCandidateRetriever
        implements AutoMemoryConsolidationCandidateRetriever {
    // Reserve half of the existing prompt capacity for the established SQL baseline.
    private static final int VECTOR_CANDIDATE_BUDGET =
            AutoMemoryExtractionInput.MAX_EXISTING_CANDIDATES / 2;

    private final AutoMemoryConsolidationCandidateRetriever sql;
    private final HydratedAutoMemoryVectorCandidateRetriever semantic;

    public CanaryAutoMemoryConsolidationCandidateRetriever(
            AutoMemoryConsolidationCandidateRetriever sql,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorCandidateHydrationPort hydration
    ) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.semantic = new HydratedAutoMemoryVectorCandidateRetriever(vectors, hydration);
    }

    @Override
    public List<AutoMemoryExtractionCandidate> retrieve(AutoMemoryConsolidationQuery query) {
        List<AutoMemoryExtractionCandidate> authoritative = sql.retrieve(query);
        try {
            HydratedAutoMemoryVectorCandidateRetriever.Result vectorResult = semantic.retrieve(
                    query,
                    Math.min(
                            VECTOR_CANDIDATE_BUDGET,
                            query.authorizedScopes().size() * query.limitPerScope()));
            return merge(vectorResult.candidates(), authoritative);
        } catch (RuntimeException ignored) {
            // A canary provider failure must leave the established SQL path unchanged.
            return authoritative;
        }
    }

    private static List<AutoMemoryExtractionCandidate> merge(
            List<AutoMemoryExtractionCandidate> vectorCandidates,
            List<AutoMemoryExtractionCandidate> sqlCandidates
    ) {
        Set<AutoMemoryExtractionCandidate> unique = new LinkedHashSet<>(vectorCandidates);
        unique.addAll(sqlCandidates);

        List<AutoMemoryExtractionCandidate> merged = new ArrayList<>(
                Math.min(unique.size(), AutoMemoryExtractionInput.MAX_EXISTING_CANDIDATES));
        for (AutoMemoryExtractionCandidate candidate : unique) {
            if (merged.size() == AutoMemoryExtractionInput.MAX_EXISTING_CANDIDATES) {
                break;
            }
            merged.add(candidate);
        }
        return List.copyOf(merged);
    }
}
