package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.util.Objects;

/** Published revision authorized for an additive projection into one BUILDING generation. */
public record CompatibilityProjectionWork(RevisionProjectionContext context,
                                          VectorGenerationProfile profile) {
    public CompatibilityProjectionWork {
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
    }

    public String workKey() {
        return workKey(profile.generationId());
    }

    public String inputFingerprint() {
        return inputFingerprint(context.revisionId(), context.retrievalManifestArtifact().contentSha256(), profile);
    }

    public static String workKey(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        return "ig:" + generationId.trim() + ":compatibility";
    }

    public static String inputFingerprint(String revisionId, String retrievalManifestSha256,
                                          VectorGenerationProfile profile) {
        if (revisionId == null || revisionId.isBlank()
                || retrievalManifestSha256 == null
                || !retrievalManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("compatibility projection identity is invalid");
        }
        VectorGenerationProfile target = Objects.requireNonNull(profile, "profile");
        return VectorGenerationProfile.sha256(revisionId.trim() + ":" + retrievalManifestSha256 + ":"
                + target.generationFingerprint() + ":" + target.tokenizerFingerprint()
                + ":BUILD_COMPATIBILITY_PROJECTION");
    }
}
