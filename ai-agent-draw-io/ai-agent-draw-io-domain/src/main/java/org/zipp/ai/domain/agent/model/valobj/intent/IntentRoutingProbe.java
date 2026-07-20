package org.zipp.ai.domain.agent.model.valobj.intent;

import org.zipp.ai.domain.retrieval.SourceMode;

/** Content-free, server-verified facts available to Intent Router V2. */
public record IntentRoutingProbe(boolean hasCanvas, int nodeCount, int edgeCount,
                                 int selectedSourceCount, int pendingSourceCount,
                                 boolean hasReadySource, boolean hasVisualEvidence,
                                 boolean selectionVersionMismatch, SourceMode sourceMode) {
    public IntentRoutingProbe {
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
    }

    public static IntentRoutingProbe empty() {
        return new IntentRoutingProbe(false, 0, 0, 0, 0,
                false, false, false, SourceMode.AUTO);
    }

    public static IntentRoutingProbe canvasOnly(boolean hasCanvas) {
        return new IntentRoutingProbe(hasCanvas, hasCanvas ? 1 : 0, 0, 0, 0,
                false, false, false, SourceMode.AUTO);
    }
}
