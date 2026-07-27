package org.zipp.ai.application.turn.context;

public record TrustedCanvasContext(
        int nodeCount,
        int edgeCount,
        String summary,
        long version,
        String contentHash,
        String canvasXml
) {

    public TrustedCanvasContext {
        if (nodeCount < 0 || edgeCount < 0) {
            throw new IllegalArgumentException("canvas counts must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("canvas version must not be negative");
        }
        summary = summary == null ? "" : summary.trim();
        if (summary.length() > 2_000) {
            throw new IllegalArgumentException("canvas summary exceeds the bounded limit");
        }
        contentHash = contentHash == null ? "" : contentHash.trim();
        canvasXml = canvasXml == null ? "" : canvasXml.trim();
    }

    /** Compatibility constructor for application tests that do not execute a Canvas edit. */
    public TrustedCanvasContext(boolean ignoredAvailable, int nodeCount, int edgeCount, String summary) {
        this(nodeCount, edgeCount, summary, 0, "", "");
    }

    /** Canvas presence is structural; a canonical empty mxGraphModel is still a non-blank string. */
    public boolean hasElements() {
        return nodeCount > 0 || edgeCount > 0;
    }
}
