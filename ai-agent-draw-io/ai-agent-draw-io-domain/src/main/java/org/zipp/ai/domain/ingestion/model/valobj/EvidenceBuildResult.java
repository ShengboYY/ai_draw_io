package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record EvidenceBuildResult(EvidenceManifest manifest, StoredArtifact manifestArtifact) {
    public EvidenceBuildResult {
        manifest = Objects.requireNonNull(manifest, "manifest");
        manifestArtifact = Objects.requireNonNull(manifestArtifact, "manifestArtifact");
    }
}
