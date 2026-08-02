package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.turn.TurnKey;

import java.util.ArrayList;
import java.util.List;

/** Scope-fenced input for selecting Memory used by one generation Turn. */
public record AutoMemoryContextQuery(
        TurnKey turn,
        String chartbookId,
        String userContent
) {
    public AutoMemoryContextQuery {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        chartbookId = nullableTrimmed(chartbookId);
        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("userContent must not be blank");
        }
        userContent = userContent.trim();
    }

    /** Narrow Chartbook context is considered before the user-global fallback. */
    public List<AutoMemoryScope> authorizedScopes() {
        List<AutoMemoryScope> scopes = new ArrayList<>(2);
        if (chartbookId != null) {
            scopes.add(AutoMemoryScope.chartbook(turn.ownerKey(), chartbookId));
        }
        scopes.add(AutoMemoryScope.user(turn.ownerKey()));
        return List.copyOf(scopes);
    }

    private static String nullableTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
