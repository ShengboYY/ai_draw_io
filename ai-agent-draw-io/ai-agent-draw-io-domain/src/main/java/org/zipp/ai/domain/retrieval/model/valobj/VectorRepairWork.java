package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.util.List;
import java.util.Objects;

/** Fenced replay of an immutable embedding batch for provider-side vector loss. */
public record VectorRepairWork(String repairId, RevisionProjectionContext context,
                               VectorGenerationProfile profile, int batchNo, String workKey,
                               String inputFingerprint, String batchInputFingerprint,
                               StoredArtifact vectorArtifact,
                               List<VectorProjectionMetadata> projections) {
    public VectorRepairWork {
        repairId = required(repairId, "repairId");
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
        if (batchNo < 0) throw new IllegalArgumentException("batchNo must be non-negative");
        workKey = required(workKey, "workKey");
        if (inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("inputFingerprint must be lowercase SHA-256");
        }
        if (batchInputFingerprint == null || !batchInputFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("batchInputFingerprint must be lowercase SHA-256");
        }
        vectorArtifact = Objects.requireNonNull(vectorArtifact, "vectorArtifact");
        projections = List.copyOf(projections);
        if (projections.isEmpty()) throw new IllegalArgumentException("repair projections are required");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
