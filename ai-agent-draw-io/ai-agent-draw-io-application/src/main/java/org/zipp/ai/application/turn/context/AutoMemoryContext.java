package org.zipp.ai.application.turn.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded long-term Memory projection. Chartbook entries are kept separate and rendered before
 * user-global entries so a narrower project preference wins without deleting the global value.
 */
public record AutoMemoryContext(
        List<String> chartbookMemories,
        List<String> userMemories
) {
    private static final int MAX_PER_SCOPE = 8;

    public AutoMemoryContext {
        chartbookMemories = bounded(chartbookMemories, "chartbookMemories");
        userMemories = bounded(userMemories, "userMemories");
    }

    public List<String> prioritizedEntries() {
        List<String> result = new ArrayList<>(
                chartbookMemories.size() + userMemories.size());
        result.addAll(chartbookMemories);
        result.addAll(userMemories);
        return List.copyOf(result);
    }

    private static List<String> bounded(List<String> values, String field) {
        List<String> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > MAX_PER_SCOPE || copy.stream().anyMatch(
                value -> value == null || value.isBlank() || value.length() > 1_500)) {
            throw new IllegalArgumentException(field + " exceeds the bounded limit");
        }
        return copy;
    }
}
