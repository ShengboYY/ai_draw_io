package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Scope and lifecycle filters applied inside the vector provider before MySQL revalidation. */
public record AutoMemoryVectorSearchQuery(
        TurnKey turn,
        String chartbookId,
        String userContent,
        Set<AutoMemoryVectorDocument.CandidateKind> kinds,
        Set<AutoMemoryVectorDocument.CandidateState> states
) {
    public AutoMemoryVectorSearchQuery {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        chartbookId = nullableTrimmed(chartbookId);
        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("userContent must not be blank");
        }
        userContent = userContent.trim();
        kinds = Set.copyOf(kinds == null ? Set.of() : kinds);
        states = Set.copyOf(states == null ? Set.of() : states);
        if (kinds.isEmpty() || states.isEmpty()) {
            throw new IllegalArgumentException("vector kind and state filters must not be empty");
        }
    }

    public static AutoMemoryVectorSearchQuery consolidation(
            AutoMemoryConsolidationQuery query
    ) {
        return new AutoMemoryVectorSearchQuery(
                query.turn(),
                query.chartbookId(),
                query.userContent(),
                Set.of(
                        AutoMemoryVectorDocument.CandidateKind.CURRENT,
                        AutoMemoryVectorDocument.CandidateKind.CHALLENGER),
                Set.of(
                        AutoMemoryVectorDocument.CandidateState.OBSERVED,
                        AutoMemoryVectorDocument.CandidateState.ACTIVE,
                        AutoMemoryVectorDocument.CandidateState.DISABLED,
                        AutoMemoryVectorDocument.CandidateState.CONFLICTING));
    }

    public static AutoMemoryVectorSearchQuery activeContext(
            TurnKey turn,
            String chartbookId,
            String userContent
    ) {
        return new AutoMemoryVectorSearchQuery(
                turn,
                chartbookId,
                userContent,
                Set.of(AutoMemoryVectorDocument.CandidateKind.CURRENT),
                Set.of(AutoMemoryVectorDocument.CandidateState.ACTIVE));
    }

    /** Only these scopes may be exposed to vector metadata filtering. */
    public List<AutoMemoryScope> authorizedScopes() {
        List<AutoMemoryScope> scopes = new ArrayList<>(2);
        scopes.add(AutoMemoryScope.user(turn.ownerKey()));
        if (chartbookId != null) {
            scopes.add(AutoMemoryScope.chartbook(turn.ownerKey(), chartbookId));
        }
        return List.copyOf(scopes);
    }

    private static String nullableTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
