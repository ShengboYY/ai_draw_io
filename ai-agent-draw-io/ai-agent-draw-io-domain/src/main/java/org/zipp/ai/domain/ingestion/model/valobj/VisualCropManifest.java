package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

/** Auditable local visual coverage manifest; it is not itself citable evidence. */
public record VisualCropManifest(String schemaVersion, String structureHash, String selectionFingerprint,
                                 int totalCandidateCount, int skippedCandidateCount,
                                 List<VisualCropArtifact> crops) {
    public VisualCropManifest {
        if (schemaVersion == null || schemaVersion.isBlank()
                || structureHash == null || !structureHash.matches("[0-9a-f]{64}")
                || selectionFingerprint == null || selectionFingerprint.isBlank()) {
            throw new IllegalArgumentException("visual crop manifest identity is invalid");
        }
        crops = List.copyOf(Objects.requireNonNull(crops, "crops"));
        if (totalCandidateCount < crops.size()
                || skippedCandidateCount != totalCandidateCount - crops.size()) {
            throw new IllegalArgumentException("visual crop manifest counts are inconsistent");
        }
    }
}
