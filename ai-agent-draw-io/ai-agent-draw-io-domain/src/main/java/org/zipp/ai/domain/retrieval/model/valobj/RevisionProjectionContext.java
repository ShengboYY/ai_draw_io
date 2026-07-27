package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

import java.util.Objects;

/** Revision, authorization, fencing, and exact Retrieval manifest facts shared by projection stages. */
/** Exact revision, lifecycle generation, and retrieval-manifest pin authorized for vector work. */
public record RevisionProjectionContext(String revisionId, String versionId, String materialId,
                                        OwnerType ownerType, String ownerKey,
                                        long revisionFenceGeneration, long materialLifecycleGeneration,
                                        String processingFingerprint, StoredArtifact retrievalManifestArtifact,
                                        boolean durableIndexEligible) {
    public RevisionProjectionContext {
        revisionId = required(revisionId, "revisionId");
        versionId = required(versionId, "versionId");
        materialId = required(materialId, "materialId");
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        ownerKey = required(ownerKey, "ownerKey");
        if (revisionFenceGeneration < 0 || materialLifecycleGeneration < 0
                || processingFingerprint == null || !processingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("revision projection generations are invalid");
        }
        retrievalManifestArtifact = Objects.requireNonNull(retrievalManifestArtifact,
                "retrievalManifestArtifact");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
