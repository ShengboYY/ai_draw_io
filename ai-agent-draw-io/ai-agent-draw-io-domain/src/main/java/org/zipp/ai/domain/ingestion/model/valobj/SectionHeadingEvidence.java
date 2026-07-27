package org.zipp.ai.domain.ingestion.model.valobj;

public record SectionHeadingEvidence(String sectionId, String evidenceId, String sourceBlockRef) {
    public SectionHeadingEvidence {
        if (sectionId == null || sectionId.isBlank() || evidenceId == null || evidenceId.isBlank()
                || sourceBlockRef == null || sourceBlockRef.isBlank()) {
            throw new IllegalArgumentException("section heading evidence identity is invalid");
        }
    }
}
