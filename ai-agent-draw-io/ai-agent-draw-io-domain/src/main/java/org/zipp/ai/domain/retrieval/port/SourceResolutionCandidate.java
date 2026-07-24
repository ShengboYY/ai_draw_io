package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;

/** Owner- and scope-authorized database projection used only while fixing a request snapshot. */
public record SourceResolutionCandidate(String declarationId, String materialId, String versionId,
                                        String revisionId, String kind, String displayName,
                                        MaterialScopeType scopeType,
                                        String scopeKey, String state, String uploadState,
                                        boolean conversationScoped, boolean hasText,
                                        boolean hasVisual, boolean pinned) {
    public SourceResolutionCandidate {
        displayName = displayName == null ? "" : displayName.trim();
    }

    /** Compatibility constructor for adapters and fixtures created before names entered the snapshot. */
    public SourceResolutionCandidate(String declarationId, String materialId, String versionId,
                                     String revisionId, String kind, MaterialScopeType scopeType,
                                     String scopeKey, String state, String uploadState,
                                     boolean conversationScoped, boolean hasText,
                                     boolean hasVisual, boolean pinned) {
        this(declarationId, materialId, versionId, revisionId, kind, "", scopeType, scopeKey,
                state, uploadState, conversationScoped, hasText, hasVisual, pinned);
    }

    public boolean ready() {
        return ("".equals(uploadState) || "SUCCEEDED".equals(uploadState))
                && ("READY".equals(state) || "PARTIAL_READY".equals(state));
    }

    /** A fixed original image may be observed before retrieval processing finishes or after it fails. */
    public boolean directReadable() {
        boolean uploadReadable = "".equals(uploadState) || "SUCCEEDED".equals(uploadState)
                || "OBJECT_VERSION_PINNED".equals(uploadState) || "PROCESSING".equals(uploadState);
        return uploadReadable && "IMAGE".equals(kind) && hasVisual
                && present(materialId) && present(versionId) && present(revisionId);
    }

    public boolean processing() {
        return "CREATED".equals(uploadState) || "OBJECT_VERSION_PINNED".equals(uploadState)
                || "PROCESSING".equals(uploadState) || "PROCESSING".equals(state);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
