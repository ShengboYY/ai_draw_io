package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifestEntry;

import java.util.List;
import java.util.Objects;

/** Complete indexed projection set that may be sealed into one generation-specific manifest. */
public record VectorManifestWork(RevisionProjectionContext context, VectorGenerationProfile profile,
                                 VectorProjectionRole projectionRole, List<VectorProjectionManifestEntry> entries) {
    public VectorManifestWork {
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
        projectionRole = Objects.requireNonNull(projectionRole, "projectionRole");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public String verificationInputFingerprint() {
        return verificationInputFingerprint(context.revisionId(), profile.generationId());
    }

    public static String verificationWorkKey(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        return "ig:" + generationId.trim();
    }

    public static String verificationInputFingerprint(String revisionId, String generationId) {
        if (revisionId == null || revisionId.isBlank()) {
            throw new IllegalArgumentException("revisionId is required");
        }
        return VectorGenerationProfile.sha256(revisionId.trim() + ":" + generationId
                + ":VERIFY_PROJECTION_MANIFEST");
    }
}
