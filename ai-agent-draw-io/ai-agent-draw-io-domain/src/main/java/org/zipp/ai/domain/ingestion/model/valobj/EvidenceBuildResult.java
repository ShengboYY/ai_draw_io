package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;
import java.util.Map;

public record EvidenceBuildResult(EvidenceManifest manifest, StoredArtifact manifestArtifact,
                                  Map<String, StoredArtifact> displayArtifacts) {
    public EvidenceBuildResult {
        manifest = Objects.requireNonNull(manifest, "manifest");
        manifestArtifact = Objects.requireNonNull(manifestArtifact, "manifestArtifact");
        displayArtifacts = Map.copyOf(displayArtifacts == null ? Map.of() : displayArtifacts);
    }

    /** Compatibility constructor for older tests and already persisted manifest-backed revisions. */
    public EvidenceBuildResult(EvidenceManifest manifest, StoredArtifact manifestArtifact) {
        this(manifest, manifestArtifact, Map.of());
    }
}
