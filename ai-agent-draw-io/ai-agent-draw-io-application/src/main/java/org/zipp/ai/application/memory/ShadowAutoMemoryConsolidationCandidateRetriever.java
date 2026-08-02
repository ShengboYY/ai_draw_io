package org.zipp.ai.application.memory;

import java.util.List;
import java.util.Objects;

/** Runs semantic retrieval for observation only and always returns the deterministic SQL result. */
public final class ShadowAutoMemoryConsolidationCandidateRetriever
        implements AutoMemoryConsolidationCandidateRetriever {
    private final AutoMemoryConsolidationCandidateRetriever sql;
    private final AutoMemoryVectorStorePort vectors;
    private final AutoMemoryVectorShadowTelemetry telemetry;

    public ShadowAutoMemoryConsolidationCandidateRetriever(
            AutoMemoryConsolidationCandidateRetriever sql,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorShadowTelemetry telemetry
    ) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
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
            telemetry.record(new AutoMemoryVectorShadowTelemetry.Sample(
                    true, authoritative.size(), vectorIds.size()));
        } catch (RuntimeException ignored) {
            telemetry.record(new AutoMemoryVectorShadowTelemetry.Sample(
                    false, authoritative.size(), 0));
        }
        return authoritative;
    }
}
