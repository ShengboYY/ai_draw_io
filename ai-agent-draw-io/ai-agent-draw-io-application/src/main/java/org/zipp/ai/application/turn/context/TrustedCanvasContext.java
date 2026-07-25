package org.zipp.ai.application.turn.context;

public record TrustedCanvasContext(
        boolean available,
        int nodeCount,
        int edgeCount,
        String summary
) {

    public TrustedCanvasContext {
        if (nodeCount < 0 || edgeCount < 0) {
            throw new IllegalArgumentException("canvas counts must not be negative");
        }
        summary = summary == null ? "" : summary.trim();
        if (summary.length() > 2_000) {
            throw new IllegalArgumentException("canvas summary exceeds the bounded limit");
        }
    }
}
