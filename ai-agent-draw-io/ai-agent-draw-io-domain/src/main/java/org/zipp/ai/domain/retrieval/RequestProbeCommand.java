package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.Objects;

/** Probe input contains opaque identifiers only; client canvas content is intentionally impossible to pass. */
public record RequestProbeCommand(CatalogOwner owner, String diagramId, String conversationId,
                                  SourceMode sourceMode, List<String> selectedVersionIds,
                                  List<String> selectedCellIds, Long selectionCanvasVersion,
                                  String selectionContentHash) {
    public RequestProbeCommand {
        Objects.requireNonNull(owner, "owner");
        diagramId = normalize(diagramId);
        conversationId = normalize(conversationId);
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        selectedVersionIds = immutableIds(selectedVersionIds);
        selectedCellIds = immutableIds(selectedCellIds);
        selectionContentHash = normalize(selectionContentHash);
        // An explicit selection always wins over a contradictory NONE hint.
        if (!selectedVersionIds.isEmpty() && sourceMode == SourceMode.NONE) sourceMode = SourceMode.EXPLICIT;
    }

    private static List<String> immutableIds(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
