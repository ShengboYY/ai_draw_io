package org.zipp.ai.application.turn.context;

import java.util.List;

public record ConversationContext(List<String> recentTurns, String summary) {

    public ConversationContext {
        recentTurns = List.copyOf(recentTurns == null ? List.of() : recentTurns);
        if (recentTurns.size() > 8 || recentTurns.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("recent conversation exceeds the bounded limit");
        }
        summary = summary == null ? "" : summary.trim();
        if (summary.length() > 6_000) {
            throw new IllegalArgumentException("conversation summary exceeds the bounded limit");
        }
    }
}
