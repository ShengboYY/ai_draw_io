package org.zipp.ai.domain.ingestion.model.valobj;

public record DocumentSection(String sectionId, String parentSectionId, int level, int ordinal,
                              int pageStart, int pageEnd, String headingBlockId, String structureHash) {
    public DocumentSection {
        if (sectionId == null || sectionId.isBlank() || level < 1 || ordinal < 1
                || pageStart < 1 || pageEnd < pageStart
                || structureHash == null || !structureHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("document section identity is invalid");
        }
        parentSectionId = blankToNull(parentSectionId);
        headingBlockId = blankToNull(headingBlockId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
