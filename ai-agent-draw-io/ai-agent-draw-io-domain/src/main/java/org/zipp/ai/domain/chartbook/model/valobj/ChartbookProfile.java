package org.zipp.ai.domain.chartbook.model.valobj;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owner-fenced, small, versioned project context. It never contains source or memory entries. */
public record ChartbookProfile(
        String chartbookId,
        long version,
        String instructions,
        String goal,
        String summary,
        Map<String, String> glossary,
        DiagramStyleDefaults defaultStyle,
        List<String> stableConstraints,
        ProfileState profileState,
        Instant updatedAt
) {

    public ChartbookProfile {
        chartbookId = required(chartbookId, "chartbookId");
        if (version < 0) throw new IllegalArgumentException("profile version must not be negative");
        instructions = bounded(instructions, "instructions", 8_000);
        goal = bounded(goal, "goal", 2_000);
        summary = bounded(summary, "summary", 4_000);
        glossary = boundedMap(glossary, 64, 128, 512, "glossary");
        defaultStyle = Objects.requireNonNull(defaultStyle, "defaultStyle");
        stableConstraints = boundedList(stableConstraints, 32, 512, "stableConstraints");
        profileState = Objects.requireNonNull(profileState, "profileState");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (profileState != stateFor(instructions, goal, summary, glossary, defaultStyle, stableConstraints)) {
            throw new IllegalArgumentException("profile state does not match profile content");
        }
    }

    public static ChartbookProfile empty(String chartbookId, Instant updatedAt) {
        return new ChartbookProfile(chartbookId, 0, "", "", "", Map.of(),
                DiagramStyleDefaults.empty(), List.of(), ProfileState.EMPTY, updatedAt);
    }

    public ChartbookProfile apply(ChartbookProfilePatch patch, Instant now) {
        Objects.requireNonNull(patch, "patch");
        if (!patch.hasChanges()) {
            throw new IllegalArgumentException("profile patch must change at least one field");
        }
        return new ChartbookProfile(
                chartbookId,
                version + 1,
                patch.instructions() == null ? instructions : patch.instructions(),
                patch.goal() == null ? goal : patch.goal(),
                patch.summary() == null ? summary : patch.summary(),
                patch.glossary() == null ? glossary : patch.glossary(),
                patch.defaultStyle() == null ? defaultStyle : patch.defaultStyle(),
                patch.stableConstraints() == null ? stableConstraints : patch.stableConstraints(),
                stateFor(
                        patch.instructions() == null ? instructions : patch.instructions(),
                        patch.goal() == null ? goal : patch.goal(),
                        patch.summary() == null ? summary : patch.summary(),
                        patch.glossary() == null ? glossary : patch.glossary(),
                        patch.defaultStyle() == null ? defaultStyle : patch.defaultStyle(),
                        patch.stableConstraints() == null ? stableConstraints : patch.stableConstraints()),
                Objects.requireNonNull(now, "now"));
    }

    private static ProfileState stateFor(String instructions, String goal, String summary,
                                         Map<String, String> glossary, DiagramStyleDefaults defaultStyle,
                                         List<String> stableConstraints) {
        return instructions.isBlank() && goal.isBlank() && summary.isBlank()
                && glossary.isEmpty() && defaultStyle.values().isEmpty() && stableConstraints.isEmpty()
                ? ProfileState.EMPTY : ProfileState.CONFIGURED;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String bounded(String value, String field, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " exceeds the bounded limit");
        return normalized;
    }

    private static Map<String, String> boundedMap(Map<String, String> source, int countLimit,
                                                  int keyLimit, int valueLimit, String field) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key == null || key.isBlank() || key.length() > keyLimit
                        || value == null || value.length() > valueLimit) {
                    throw new IllegalArgumentException(field + " contains an invalid entry");
                }
                copy.put(key.trim(), value.trim());
            });
        }
        if (copy.size() > countLimit) throw new IllegalArgumentException(field + " exceeds the bounded entry limit");
        return Map.copyOf(copy);
    }

    private static List<String> boundedList(List<String> source, int countLimit, int itemLimit, String field) {
        List<String> copy = List.copyOf(source == null ? List.of() : source);
        if (copy.size() > countLimit || copy.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > itemLimit)) {
            throw new IllegalArgumentException(field + " exceeds the bounded list limit");
        }
        return copy.stream().map(String::trim).toList();
    }
}
