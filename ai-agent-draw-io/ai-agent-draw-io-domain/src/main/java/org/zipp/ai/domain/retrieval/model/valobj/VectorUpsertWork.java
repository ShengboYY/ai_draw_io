package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.util.List;
import java.util.Objects;

/** Exact vector artifact and authoritative metadata authorized for an idempotent index upsert. */
public record VectorUpsertWork(RevisionProjectionContext context, VectorGenerationProfile profile,
                               int batchNo, String workKey, String batchInputFingerprint,
                               StoredArtifact vectorArtifact, List<VectorProjectionMetadata> projections) {
    public VectorUpsertWork {
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
        vectorArtifact = Objects.requireNonNull(vectorArtifact, "vectorArtifact");
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        if (batchNo < 0 || workKey == null || workKey.isBlank()
                || batchInputFingerprint == null || !batchInputFingerprint.matches("[0-9a-f]{64}")
                || projections.isEmpty()) {
            throw new IllegalArgumentException("vector upsert work identity is invalid");
        }
    }

    public String upsertInputFingerprint() {
        return upsertInputFingerprint(workKey, batchInputFingerprint, vectorArtifact.contentSha256());
    }

    public static String upsertInputFingerprint(String workKey, String batchInputFingerprint,
                                                String vectorArtifactSha256) {
        return org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile.sha256(
                workKey + ":" + batchInputFingerprint + ":" + vectorArtifactSha256
                        + ":UPSERT_VECTOR_BATCHES");
    }
}
