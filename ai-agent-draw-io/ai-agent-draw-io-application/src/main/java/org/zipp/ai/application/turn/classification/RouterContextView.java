package org.zipp.ai.application.turn.classification;

import java.util.List;

/**
 * Safe, server-owned context facts visible to the Semantic Router. Source availability, source
 * content, attachments, snapshots, and evidence are intentionally absent.
 */
public record RouterContextView(
        boolean canvasAvailable,
        int canvasNodeCount,
        int canvasEdgeCount,
        boolean selectionAvailable,
        int recentMessageCount,
        boolean profileAvailable,
        boolean memoryAvailable,
        String canvasSummary,
        List<String> recentTurns,
        String conversationSummary,
        String chartbookMembership,
        String profileInstructions,
        String profileGoal,
        String profileSummary,
        List<String> profileGlossary,
        String profileDefaultStyle,
        List<String> profileStableConstraints,
        List<String> confirmedMemoryDecisions
) {

    /** Compatibility constructor for the first application-only classification slice. */
    public RouterContextView(
            boolean canvasAvailable,
            boolean selectionAvailable,
            int recentMessageCount,
            boolean profileAvailable,
            boolean memoryAvailable
    ) {
        this(canvasAvailable, 0, 0, selectionAvailable, recentMessageCount, profileAvailable, memoryAvailable,
                "", List.of(), "", "", "", "", "", List.of(), "", List.of(), List.of());
    }

    /** Compatibility constructor for callers compiled against the pre-Profile projection. */
    public RouterContextView(
            boolean canvasAvailable,
            boolean selectionAvailable,
            int recentMessageCount,
            boolean profileAvailable,
            boolean memoryAvailable,
            String canvasSummary,
            List<String> recentTurns,
            String conversationSummary,
            String chartbookMembership,
            String profileInstructions,
            String profileGoal,
            String profileSummary,
            List<String> profileGlossary,
            String profileDefaultStyle,
            List<String> confirmedMemoryDecisions
    ) {
        this(canvasAvailable, 0, 0, selectionAvailable, recentMessageCount, profileAvailable, memoryAvailable,
                canvasSummary, recentTurns, conversationSummary, chartbookMembership, profileInstructions,
                profileGoal, profileSummary, profileGlossary, profileDefaultStyle, List.of(),
                confirmedMemoryDecisions);
    }

    public RouterContextView {
        if (canvasNodeCount < 0 || canvasEdgeCount < 0 || recentMessageCount < 0) {
            throw new IllegalArgumentException("router context counts must not be negative");
        }
        canvasSummary = bounded(canvasSummary, 2_000);
        recentTurns = boundedList(recentTurns, 8, 4_000);
        conversationSummary = bounded(conversationSummary, 6_000);
        chartbookMembership = bounded(chartbookMembership, 256);
        profileInstructions = bounded(profileInstructions, 8_000);
        profileGoal = bounded(profileGoal, 2_000);
        profileSummary = bounded(profileSummary, 4_000);
        profileGlossary = boundedList(profileGlossary, 64, 1_000);
        profileDefaultStyle = bounded(profileDefaultStyle, 12_000);
        profileStableConstraints = boundedList(profileStableConstraints, 32, 512);
        confirmedMemoryDecisions = boundedList(confirmedMemoryDecisions, 8, 1_500);
        if (recentMessageCount < recentTurns.size()) {
            throw new IllegalArgumentException("recentMessageCount cannot be below visible recent turns");
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > limit) {
            throw new IllegalArgumentException("router context text exceeds the bounded limit");
        }
        return normalized;
    }

    private static List<String> boundedList(List<String> values, int countLimit, int itemLimit) {
        List<String> copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > countLimit || copy.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > itemLimit)) {
            throw new IllegalArgumentException("router context list exceeds the bounded limit");
        }
        return copy;
    }
}
