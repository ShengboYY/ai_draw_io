package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

import java.util.Objects;

/** Exact Evidence manifest pin and authorization scope consumed by retrieval projection. */
public record RevisionRetrievalWork(String revisionId, String versionId, String materialId,
                                    OwnerType ownerType, String ownerKey,
                                    long revisionFenceGeneration, long materialLifecycleGeneration,
                                    String processingFingerprint, StoredArtifact evidenceManifestArtifact) {
    public RevisionRetrievalWork {
        revisionId = requireText(revisionId, "revisionId");
        versionId = requireText(versionId, "versionId");
        materialId = requireText(materialId, "materialId");
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        ownerKey = requireText(ownerKey, "ownerKey");
        if (revisionFenceGeneration < 0 || materialLifecycleGeneration < 0
                || processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision retrieval work generations are invalid");
        }
        evidenceManifestArtifact = Objects.requireNonNull(evidenceManifestArtifact, "evidenceManifestArtifact");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
