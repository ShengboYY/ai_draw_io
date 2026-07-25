package org.zipp.ai.application.turn.context;

import java.util.List;

public record ChartbookProfileContext(
        String instructions,
        String goal,
        String summary,
        List<String> glossary,
        String defaultStyle
) {

    public ChartbookProfileContext {
        instructions = bounded(instructions, "instructions", 8_000);
        goal = bounded(goal, "goal", 2_000);
        summary = bounded(summary, "summary", 4_000);
        glossary = List.copyOf(glossary == null ? List.of() : glossary);
        if (glossary.size() > 64 || glossary.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("glossary exceeds the bounded limit");
        }
        defaultStyle = bounded(defaultStyle, "defaultStyle", 2_000);
    }

    private static String bounded(String value, String field, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) {
            throw new IllegalArgumentException(field + " exceeds the bounded limit");
        }
        return normalized;
    }
}
