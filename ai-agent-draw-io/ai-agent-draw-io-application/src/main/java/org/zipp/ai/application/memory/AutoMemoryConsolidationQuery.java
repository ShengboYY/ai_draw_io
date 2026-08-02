package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;

/** Scope-fenced input shared by bounded SQL and future semantic candidate retrieval. */
public record AutoMemoryConsolidationQuery(
        TurnKey turn,
        String chartbookId,
        String userContent,
        int limitPerScope
) {
    public static final int MAX_CANDIDATES_PER_SCOPE = 16;

    public AutoMemoryConsolidationQuery {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        chartbookId = nullableTrimmed(chartbookId);
        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("userContent must not be blank");
        }
        userContent = userContent.trim();
        if (limitPerScope < 1 || limitPerScope > MAX_CANDIDATES_PER_SCOPE) {
            throw new IllegalArgumentException("limitPerScope must be between 1 and 16");
        }
    }

    /** Only these scopes may be exposed to a retriever or vector metadata filter. */
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
