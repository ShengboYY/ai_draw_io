package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

import java.util.Objects;

/** Exact visual artifact identity plus its citable source anchor. */
public record VisualObservationTarget(String evidenceId, String materialId, String versionId,
                                      String revisionId, int pageNumber, String sourceLabel,
                                      StoredArtifact artifact) {
    public VisualObservationTarget {
        evidenceId = required(evidenceId, "evidenceId");
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        revisionId = required(revisionId, "revisionId");
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
        sourceLabel = required(sourceLabel, "sourceLabel");
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (!artifact.contentType().startsWith("image/")) {
            throw new IllegalArgumentException("visual artifact must be an image");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
