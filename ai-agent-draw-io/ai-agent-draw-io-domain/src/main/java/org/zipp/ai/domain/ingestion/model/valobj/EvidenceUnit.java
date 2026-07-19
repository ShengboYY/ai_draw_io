package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

/** A citable fact boundary whose source always remains pinned to immutable revision content. */
public record EvidenceUnit(String evidenceId, String pageId, int pageNo, String sectionId,
                           EvidenceUnitType unitType, EvidenceModality modality,
                           String sourceChannel, String displayText, String displayTextSha256,
                           StoredArtifact sourceArtifact, StoredArtifact visualArtifact,
                           List<EvidenceRegion> regions, double quality) {
    public EvidenceUnit {
        if (evidenceId == null || evidenceId.isBlank() || pageId == null || pageId.isBlank()
                || pageNo < 1 || quality < 0 || quality > 1) {
            throw new IllegalArgumentException("evidence unit identity is invalid");
        }
        sectionId = sectionId == null || sectionId.isBlank() ? null : sectionId.trim();
        unitType = Objects.requireNonNull(unitType, "unitType");
        modality = Objects.requireNonNull(modality, "modality");
        sourceChannel = requireText(sourceChannel, "sourceChannel");
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        if (regions.isEmpty() || regions.stream().anyMatch(region -> !pageId.equals(region.pageId()))) {
            throw new IllegalArgumentException("evidence unit requires regions on its source page");
        }
        if (modality == EvidenceModality.TEXT) {
            if (!("NATIVE".equals(sourceChannel) || "OCR".equals(sourceChannel))
                    || displayText == null || displayText.isBlank()
                    || displayTextSha256 == null || !displayTextSha256.matches("[0-9a-f]{64}")
                    || sourceArtifact == null || visualArtifact != null) {
                throw new IllegalArgumentException("text evidence source is invalid");
            }
        } else if (!"VISUAL".equals(sourceChannel) || displayText != null
                || displayTextSha256 != null || sourceArtifact != null || visualArtifact == null) {
            throw new IllegalArgumentException("visual evidence source is invalid");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
