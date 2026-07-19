package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

import java.util.Objects;

/** Newly embedded batch together with its exact immutable S3 artifact pin. */
public record VectorBatchArtifactResult(VectorBatchPayload payload, StoredArtifact artifact) {
    public VectorBatchArtifactResult {
        payload = Objects.requireNonNull(payload, "payload");
        artifact = Objects.requireNonNull(artifact, "artifact");
    }
}
