package org.zipp.ai.domain.citation.model.entity;

public record CitationSourceTombstone(String opaqueMaterialId, String opaqueVersionId, int versionNo) {

    public CitationSourceTombstone {
        opaqueMaterialId = requireText(opaqueMaterialId, "opaqueMaterialId");
        opaqueVersionId = requireText(opaqueVersionId, "opaqueVersionId");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
