package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record VisualProcessingResult(VisualCropManifest manifest, StoredArtifact manifestArtifact) {
    public VisualProcessingResult {
        manifest = Objects.requireNonNull(manifest, "manifest");
        manifestArtifact = Objects.requireNonNull(manifestArtifact, "manifestArtifact");
    }
}
