package org.zipp.ai.application.turn.classification;

/**
 * Safe, server-owned context facts visible to the Semantic Router. Source availability and
 * source content are intentionally absent.
 */
public record RouterContextView(
        boolean canvasAvailable,
        boolean selectionAvailable,
        int recentMessageCount,
        boolean profileAvailable,
        boolean memoryAvailable
) {

    public RouterContextView {
        if (recentMessageCount < 0) {
            throw new IllegalArgumentException("recentMessageCount must not be negative");
        }
    }
}
