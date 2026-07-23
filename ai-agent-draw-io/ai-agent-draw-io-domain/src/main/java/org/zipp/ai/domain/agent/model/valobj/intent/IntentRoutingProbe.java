package org.zipp.ai.domain.agent.model.valobj.intent;

import org.zipp.ai.domain.retrieval.SourceMode;

/** Content-free, server-verified facts available to Intent Router V2. */
public record IntentRoutingProbe(boolean hasCanvas, int nodeCount, int edgeCount,
                                 int selectedSourceCount, int pendingSourceCount,
                                 boolean hasReadySource, boolean hasVisualEvidence,
                                 boolean selectionVersionMismatch, SourceMode sourceMode,
                                 int attachmentCount, int readyAttachmentCount,
                                 int pendingAttachmentCount, boolean hasSingleReadyImageAttachment,
                                 boolean hasPdfAttachment) {
    public IntentRoutingProbe {
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        attachmentCount = Math.max(0, attachmentCount);
        readyAttachmentCount = Math.max(0, readyAttachmentCount);
        pendingAttachmentCount = Math.max(0, pendingAttachmentCount);
    }

    /** Compatibility constructor for callers that do not yet provide attachment facts. */
    public IntentRoutingProbe(boolean hasCanvas, int nodeCount, int edgeCount,
                              int selectedSourceCount, int pendingSourceCount,
                              boolean hasReadySource, boolean hasVisualEvidence,
                              boolean selectionVersionMismatch, SourceMode sourceMode) {
        this(hasCanvas, nodeCount, edgeCount, selectedSourceCount, pendingSourceCount,
                hasReadySource, hasVisualEvidence, selectionVersionMismatch, sourceMode,
                0, 0, 0, false, false);
    }

    public static IntentRoutingProbe empty() {
        return new IntentRoutingProbe(false, 0, 0, 0, 0,
                false, false, false, SourceMode.AUTO, 0, 0, 0, false, false);
    }

    public static IntentRoutingProbe canvasOnly(boolean hasCanvas) {
        return new IntentRoutingProbe(hasCanvas, hasCanvas ? 1 : 0, 0, 0, 0,
                false, false, false, SourceMode.AUTO, 0, 0, 0, false, false);
    }
}
