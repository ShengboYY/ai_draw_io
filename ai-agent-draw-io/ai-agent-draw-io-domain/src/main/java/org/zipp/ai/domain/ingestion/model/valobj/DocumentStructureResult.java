package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

/** Immutable structure manifest plus its exact object-store version. */
public record DocumentStructureResult(DocumentStructure structure, StoredArtifact artifact) {
    public DocumentStructureResult {
        structure = Objects.requireNonNull(structure, "structure");
        artifact = Objects.requireNonNull(artifact, "artifact");
    }
}
