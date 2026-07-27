package org.zipp.ai.domain.retrieval;

import java.util.List;

/** Server-derived canvas metadata. It deliberately contains no XML or user-authored labels. */
public record CanvasProbe(boolean hasCanvas, int nodeCount, int edgeCount, Long serverCanvasVersion,
                          String contentHash, int selectedCellCount, List<String> validatedSelectedKinds,
                          boolean selectionVersionMismatch, boolean unavailable) {
    public CanvasProbe {
        validatedSelectedKinds = List.copyOf(validatedSelectedKinds == null ? List.of() : validatedSelectedKinds);
        contentHash = contentHash == null ? "" : contentHash;
    }

    public static CanvasProbe unavailableProbe() {
        return new CanvasProbe(false, 0, 0, null, "", 0, List.of(), false, true);
    }
}
