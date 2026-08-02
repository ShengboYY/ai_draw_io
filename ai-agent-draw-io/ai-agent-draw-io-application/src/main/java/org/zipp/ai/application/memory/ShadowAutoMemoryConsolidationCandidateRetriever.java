package org.zipp.ai.application.memory;

import java.util.List;
import java.util.Objects;

/** Runs semantic retrieval for observation only and always returns the deterministic SQL result. */
public final class ShadowAutoMemoryConsolidationCandidateRetriever
        implements AutoMemoryConsolidationCandidateRetriever {
    private final AutoMemoryConsolidationCandidateRetriever sql;
    private final AutoMemoryVectorStorePort vectors;
    private final AutoMemoryVectorCandidateHydrationPort hydration;
    private final AutoMemoryVectorShadowTelemetry telemetry;

    public ShadowAutoMemoryConsolidationCandidateRetriever(
            AutoMemoryConsolidationCandidateRetriever sql,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorCandidateHydrationPort hydration,
            AutoMemoryVectorShadowTelemetry telemetry
    ) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.hydration = Objects.requireNonNull(hydration, "hydration");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public List<AutoMemoryExtractionCandidate> retrieve(AutoMemoryConsolidationQuery query) {
        List<AutoMemoryExtractionCandidate> authoritative = sql.retrieve(query);
        try {
            List<String> vectorIds = vectors.search(
                    query,
                    Math.min(
                            AutoMemoryExtractionInput.MAX_EXISTING_CANDIDATES,
                            query.authorizedScopes().size() * query.limitPerScope()));
            List<AutoMemoryExtractionCandidate> hydrated = hydration.hydrate(query, vectorIds);
            long overlap = hydrated.stream().filter(authoritative::contains).count();
            recordSafely(new AutoMemoryVectorShadowTelemetry.Sample(
                    true,
                    authoritative.size(),
                    vectorIds.size(),
                    hydrated.size(),
                    Math.toIntExact(overlap)));
        } catch (RuntimeException ignored) {
            recordSafely(new AutoMemoryVectorShadowTelemetry.Sample(
                    false, authoritative.size(), 0, 0, 0));
        }
        return authoritative;
    }

    private void recordSafely(AutoMemoryVectorShadowTelemetry.Sample sample) {
        try {
            telemetry.record(sample);
        } catch (RuntimeException ignored) {
            // Observability is deliberately weaker than the authoritative SQL candidate path.
        }
    }
}
