package org.zipp.ai.application.turn.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative non-model fallback for requests with explicit clause boundaries. */
public final class AutoMemoryRecallClauseFallback {

    public List<String> facets(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return List.of();
        }
        String[] clauses = userContent.split("[;；\\n]+", -1);
        if (clauses.length < 2 || clauses.length > AutoMemoryRecallPlanner.MAX_SUBQUERIES) {
            return List.of();
        }
        List<String> result = new ArrayList<>(clauses.length);
        Set<String> unique = new LinkedHashSet<>();
        for (String clause : clauses) {
            String trimmed = clause.trim();
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || trimmed.length() > 500 || !unique.add(normalized)) {
                return List.of();
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }
}
