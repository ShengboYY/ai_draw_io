package org.zipp.ai.domain.chartbook.model.valobj;

import java.time.Instant;
import java.util.Set;

/** Owner-safe chartbook projection; shared means within one user's book, never multi-user access. */
public record ChartbookView(String chartbookId, String ownerKey, String name, ChartbookStatus status,
                            Set<String> diagramIds, Set<String> materialIds,
                            Instant createdAt, Instant updatedAt) {
    public ChartbookView {
        if (chartbookId == null || chartbookId.isBlank() || ownerKey == null || ownerKey.isBlank()
                || name == null || name.isBlank() || status == null) {
            throw new IllegalArgumentException("chartbook view is invalid");
        }
        chartbookId = chartbookId.trim();
        ownerKey = ownerKey.trim();
        name = name.trim();
        diagramIds = Set.copyOf(diagramIds);
        materialIds = Set.copyOf(materialIds);
    }
}
