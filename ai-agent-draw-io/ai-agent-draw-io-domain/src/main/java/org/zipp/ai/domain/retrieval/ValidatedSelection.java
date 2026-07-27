package org.zipp.ai.domain.retrieval;

import java.util.List;

public record ValidatedSelection(List<String> cellIds, Long canvasVersion, String contentHash) {
    public ValidatedSelection {
        cellIds = cellIds == null ? List.of() : cellIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        contentHash = contentHash == null ? "" : contentHash.trim();
    }

    public static ValidatedSelection empty() {
        return new ValidatedSelection(List.of(), null, "");
    }
}
