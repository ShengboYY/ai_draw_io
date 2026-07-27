package org.zipp.ai.domain.material.model.valobj;

import java.util.List;
import java.util.Set;

public record MaterialPageSet(String materialId, String versionId, String revisionId, int revisionNo,
                              CatalogProcessingStatus processingStatus, int progress,
                              Set<Integer> excludedPages, List<MaterialPageSummary> pages) {
    public MaterialPageSet {
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        revisionId = required(revisionId, "revisionId");
        if (revisionNo < 1 || processingStatus == null || progress < 0 || progress > 100) {
            throw new IllegalArgumentException("material page set is invalid");
        }
        excludedPages = Set.copyOf(excludedPages);
        pages = List.copyOf(pages);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
