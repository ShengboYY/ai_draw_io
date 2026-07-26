package org.zipp.ai.application.turn.context;

import java.util.List;

public record ChartbookProfileContext(
        String instructions,
        String goal,
        String summary,
        List<String> glossary,
        String defaultStyle,
        List<String> stableConstraints
) {

    /** Keeps existing Context callers source-compatible while the Profile contract grows. */
    public ChartbookProfileContext(String instructions, String goal, String summary,
                                   List<String> glossary, String defaultStyle) {
        this(instructions, goal, summary, glossary, defaultStyle, List.of());
    }

    public ChartbookProfileContext {
        instructions = bounded(instructions, "instructions", 8_000);
        goal = bounded(goal, "goal", 2_000);
        summary = bounded(summary, "summary", 4_000);
        glossary = List.copyOf(glossary == null ? List.of() : glossary);
        if (glossary.size() > 64 || glossary.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("glossary exceeds the bounded limit");
        }
        defaultStyle = bounded(defaultStyle, "defaultStyle", 12_000);
        stableConstraints = List.copyOf(stableConstraints == null ? List.of() : stableConstraints);
        if (stableConstraints.size() > 32 || stableConstraints.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512)) {
            throw new IllegalArgumentException("stableConstraints exceeds the bounded limit");
        }
    }

    private static String bounded(String value, String field, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) {
            throw new IllegalArgumentException(field + " exceeds the bounded limit");
        }
        return normalized;
    }
}
