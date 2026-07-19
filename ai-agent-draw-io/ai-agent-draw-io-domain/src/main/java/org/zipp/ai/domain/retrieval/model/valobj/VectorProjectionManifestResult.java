package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifest;

import java.util.Objects;

/** Sealed generation manifest together with its exact immutable S3 artifact pin. */
public record VectorProjectionManifestResult(VectorProjectionManifest manifest, StoredArtifact artifact) {
    public VectorProjectionManifestResult {
        manifest = Objects.requireNonNull(manifest, "manifest");
        artifact = Objects.requireNonNull(artifact, "artifact");
    }
}
