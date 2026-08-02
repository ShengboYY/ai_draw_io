package org.zipp.ai.application.turn.context;

import java.util.ArrayList;
import java.util.List;

/** Bounded long-term Memory projection in the exact order selected for one Turn. */
public record AutoMemoryContext(List<Entry> entries) {
    private static final int MAX_PER_SCOPE = 8;
    private static final int MAX_TOTAL = MAX_PER_SCOPE * 2;

    public AutoMemoryContext {
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (entries.size() > MAX_TOTAL
                || count(entries, Scope.CHARTBOOK) > MAX_PER_SCOPE
                || count(entries, Scope.USER) > MAX_PER_SCOPE) {
            throw new IllegalArgumentException("Memory context exceeds the bounded limit");
        }
    }

    /** Compatibility constructor for callers that already group entries by scope. */
    public AutoMemoryContext(List<String> chartbookMemories, List<String> userMemories) {
        this(grouped(chartbookMemories, userMemories));
    }

    public List<String> chartbookMemories() {
        return values(Scope.CHARTBOOK);
    }

    public List<String> userMemories() {
        return values(Scope.USER);
    }

    public List<String> prioritizedEntries() {
        return entries.stream().map(Entry::value).toList();
    }

    private List<String> values(Scope scope) {
        return entries.stream()
                .filter(entry -> entry.scope() == scope)
                .map(Entry::value)
                .toList();
    }

    private static List<Entry> grouped(
            List<String> chartbookMemories,
            List<String> userMemories
    ) {
        List<Entry> result = new ArrayList<>();
        add(result, Scope.CHARTBOOK, chartbookMemories);
        add(result, Scope.USER, userMemories);
        return List.copyOf(result);
    }

    private static void add(List<Entry> target, Scope scope, List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            target.add(new Entry(scope, value));
        }
    }

    private static long count(List<Entry> entries, Scope scope) {
        return entries.stream().filter(entry -> entry.scope() == scope).count();
    }

    public enum Scope {
        CHARTBOOK,
        USER
    }

    public record Entry(Scope scope, String value) {
        public Entry {
            if (scope == null || value == null || value.isBlank() || value.length() > 1_500) {
                throw new IllegalArgumentException("invalid Memory context entry");
            }
            value = value.trim();
        }
    }
}
