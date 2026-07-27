package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;

import java.time.Instant;

public record MaterialReprocessPlan(CatalogOwner owner, String materialId,
                                    String expectedLatestRevisionId, String requestFingerprint,
                                    ProcessingRevision revision,
                                    ProcessingRevisionProfile processingProfile,
                                    ProcessingJob extractionJob, Instant requestedAt) {
    public MaterialReprocessPlan {
        if (owner == null || requestedAt == null) throw new IllegalArgumentException("reprocess owner/time required");
        materialId = required(materialId, "materialId");
        expectedLatestRevisionId = required(expectedLatestRevisionId, "expectedLatestRevisionId");
        requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
        if (revision == null || processingProfile == null || extractionJob == null) {
            throw new IllegalArgumentException("reprocess revision, profile and extraction job are required");
        }
        if (!revision.id().equals(extractionJob.target().revisionId())) {
            throw new IllegalArgumentException("reprocess job must target the planned revision");
        }
    }

    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " invalid");
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
