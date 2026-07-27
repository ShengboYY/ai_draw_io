package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Immutable local crop tied to its source page and structure candidate. */
public record VisualCropArtifact(String pageId, VisualCandidate candidate, StoredArtifact artifact) {
    public VisualCropArtifact {
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("visual crop pageId is required");
        }
        candidate = Objects.requireNonNull(candidate, "candidate");
        artifact = Objects.requireNonNull(artifact, "artifact");
    }
}
