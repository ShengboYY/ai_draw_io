package org.zipp.ai.domain.retrieval;

import java.util.List;

/** Content-free source facts safe to expose to the intent router. */
public record SourceProbe(int selectedCount, List<String> selectedKinds, List<String> selectedStates,
                          int pendingConversationUploadCount, boolean hasReadyDiagramSources,
                          boolean hasReadyChartbookSources, boolean hasReadyLibrarySources,
                          boolean hasPinnedCitations, boolean hasVisualEvidence,
                          int partialReadyCount, SourceMode effectiveSourceMode) {
    public SourceProbe {
        selectedKinds = List.copyOf(selectedKinds == null ? List.of() : selectedKinds);
        selectedStates = List.copyOf(selectedStates == null ? List.of() : selectedStates);
        effectiveSourceMode = effectiveSourceMode == null ? SourceMode.AUTO : effectiveSourceMode;
    }

    public static SourceProbe empty(SourceMode mode) {
        return new SourceProbe(0, List.of(), List.of(), 0, false, false,
                false, false, false, 0, mode);
    }
}
