package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;

import java.util.Objects;

/** One exact material version/revision fixed by the server for the lifetime of a run. */
public record ResolvedSource(String materialId, String versionId, String revisionId, String kind,
                             String displayName, MaterialScopeType scopeType, String scopeKey, String state,
                             RequestSourceOrigin origin, boolean hasText, boolean hasVisual,
                             boolean pinned, boolean countsAsProcessingSource) {
    public ResolvedSource {
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        revisionId = required(revisionId, "revisionId");
        kind = required(kind, "kind");
        displayName = displayName == null ? "" : displayName.trim();
        Objects.requireNonNull(scopeType, "scopeType");
        scopeKey = required(scopeKey, "scopeKey");
        state = required(state, "state");
        Objects.requireNonNull(origin, "origin");
    }

    /** Compatibility constructor that derives the legacy processing contribution from revision state. */
    public ResolvedSource(String materialId, String versionId, String revisionId, String kind,
                          String displayName, MaterialScopeType scopeType, String scopeKey, String state,
                          RequestSourceOrigin origin, boolean hasText, boolean hasVisual,
                          boolean pinned) {
        this(materialId, versionId, revisionId, kind, displayName, scopeType, scopeKey, state,
                origin, hasText, hasVisual, pinned, "PROCESSING".equals(state));
    }

    /** Compatibility constructor for callers that do not need deterministic name matching. */
    public ResolvedSource(String materialId, String versionId, String revisionId, String kind,
                          MaterialScopeType scopeType, String scopeKey, String state,
                          RequestSourceOrigin origin, boolean hasText, boolean hasVisual,
                          boolean pinned) {
        this(materialId, versionId, revisionId, kind, "", scopeType, scopeKey, state,
                origin, hasText, hasVisual, pinned, "PROCESSING".equals(state));
    }

    public boolean ready() {
        return "READY".equals(state) || "PARTIAL_READY".equals(state);
    }

    /** Direct reconstruction needs a readable original image, not retrieval processing readiness. */
    public boolean directReadable() {
        return "IMAGE".equals(kind) && hasVisual;
    }

    public boolean declared() {
        return origin == RequestSourceOrigin.ATTACHMENT || origin == RequestSourceOrigin.EXPLICIT;
    }

    public AuthorizedSource authorizedSource() {
        return new AuthorizedSource(materialId, versionId, revisionId, scopeType, scopeKey, state,
                scopeType == MaterialScopeType.CONVERSATION, declared(), hasText, hasVisual);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
