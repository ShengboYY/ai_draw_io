package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;

import java.util.Set;

/** Locked-again-by-adapter source facts used to plan a new immutable processing revision. */
public record MaterialReprocessSnapshot(String materialId, String versionId, Integer pageCount,
                                        String contentSha256, String latestRevisionId,
                                        int latestRevisionNo, String latestRevisionState,
                                        String processingFingerprint,
                                        ProcessingRevisionProfile processingProfile,
                                        Set<Integer> excludedPages) {
    public MaterialReprocessSnapshot {
        materialId = required(materialId, "materialId");
        versionId = required(versionId, "versionId");
        contentSha256 = fingerprint(contentSha256, "contentSha256");
        latestRevisionId = required(latestRevisionId, "latestRevisionId");
        processingFingerprint = fingerprint(processingFingerprint, "processingFingerprint");
        if (processingProfile == null) throw new IllegalArgumentException("processingProfile is required");
        latestRevisionState = required(latestRevisionState, "latestRevisionState");
        excludedPages = Set.copyOf(excludedPages);
        if (latestRevisionNo < 1) throw new IllegalArgumentException("latestRevisionNo is invalid");
    }

    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
